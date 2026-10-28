# JavaFX 无障碍生命周期与属性契约修补

## 原因与范围

Windows UI Automation 的客户端可以保留文本范围，而原控件已经离开场景。
JavaFX 25.0.5 的 `Accessible.dispose()` 会清空事件处理器；保留的
`WinTextRangeProvider.GetText()` 随后仍会进入 `Accessible.getAttribute()`，导致空指针异常。
仅关闭窗口、关闭无障碍或捕获并忽略异常，都不能修复这个生命周期问题。

本项目对 `Accessible` 的两个入口增加释放保护，等价源码如下：

```java
public Object getAttribute(AccessibleAttribute attribute, Object... parameters) {
    if (eventHandler == null) return null;
    // 原实现不变。
}

public void executeAction(AccessibleAction action, Object... parameters) {
    if (eventHandler == null) return;
    // 原实现不变。
}
```

已释放对象不再提供属性或接受操作；仍有效的对象继续执行原实现，异常也继续传播。
控件重新挂载时由 JavaFX 创建新的无障碍对象，不保留旧控件、不延迟资源释放。

## Windows 属性返回值

管理员 ETW 采样经微软公开符号解析后，密集遍历的主要调用链为
`UIAutomationCore!GeneralProviderError → GetProviderInfo → ReadProcessMemory`。
核对 [GetPropertyValue 契约](https://learn.microsoft.com/en-us/windows/win32/api/uiautomationcore/nf-uiautomationcore-irawelementprovidersimple-getpropertyvalue)
和 [GlassAccessible.cpp](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/native-glass/win/GlassAccessible.cpp)
发现：JavaFX 对不支持的属性返回 Java `null`，原生桥将其转成 `VT_EMPTY` **和 `E_FAIL`**；
微软要求不支持的属性返回 `VT_EMPTY` **和 `S_OK`**。错误路径会查询提供者信息，造成额外的原生内存读取。

仅在 Windows 的 `WinAccessible.GetPropertyValue` 返回处修正：保留非空值；若结果为空且对象
仍有效，返回新的 `WinVariant`（默认 `vt=0`，即 `VT_EMPTY`）。原生桥因此正常返回 `S_OK`。
已释放对象仍返回原来的失败；活动处理器抛出的异常不被捕获。未知的未来属性编号遵循同一契约，
没有关闭 UIA、过滤日志或把所有失败改成成功。

另一个上游原生行为仍保留：轻量控件的宿主句柄为 0，Glass 调用 `UiaHostProviderFromHwnd(NULL)`
后忽略它的 `E_INVALIDARG`。新追踪仍看到这条诊断路径；不能将属性修补描述为整个 Windows UIA
实现都不再有额外开销。为这一行为重建全部 Glass 原生库会扩大依赖与工具链范围，本次未替换它。

属性修补后，长日志仍暴露 `NavigateListView → ITEM_AT_INDEX → VirtualFlow.getPrivateCell` 的
屏外单元格创建。应用层将日志限制为每页 100 行，全部已读取内容仍可翻页；该限制不更改无障碍角色，
不隐去内容。完整 1500 行的顺序、可达性和 50 ms 门禁一起验证，见 [流畅度](performance.md)。

## 构建与来源

- 上游：[Accessible.java](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/java/com/sun/glass/ui/Accessible.java)、
  [WinTextRangeProvider.java](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/java/com/sun/glass/ui/win/WinTextRangeProvider.java)。
- 原依赖仍为 Maven Central 的 `org.openjfx:javafx-graphics:25.0.5`，许可为 GPL v2 with Classpath Exception。
  这是一项本项目维护的修补，不是上游已发布的修复版本。
- Maven 先复制原始模块到 `desktop/target/javafx-original`，然后运行
  `cicd/desktop/PatchJavafx.java`，通过 JDK 25 的标准 Class-File API 生成私有模块路径
  `desktop/target/javafx`。不修改 Maven 缓存，也不在运行中注入 agent。
- 仅接受已复核的 25.0.5 模块；原始 `Accessible.class` 的 SHA-256 必须为
  `fb5dc4cd2c8a8e3b1e495f8e5a58a098d679686b31e1bcae4f4eed05eef09ffb`。
  不匹配立即停止构建。生成类通过字节码校验，工件记录原始与修补后校验和。
- Windows 另核验 `WinAccessible.class` 的原始 SHA-256：
  `4bf09599b2679ce7d66902c3d83eda0f492683700c500d004211af741ce7cea2`，只变换一个指定签名的方法。
  标记升级为 `accessible-contracts-v2`；旧标记、缺失或修改过的 Windows 类均拒绝打包。
- 测试、开发启动与 jlink 打包都使用修补模块。打包额外核验修补标记及类文件校验和；
  保留 AOT 训练与实际缓存使用检查。

## 回归与移除条件

`AccessibilityLifecycleTest` 在两平台检查释放后查询与操作安全，且活动处理器的异常继续传播。
Windows 另检查真实场景移除后的查询、过期编辑无效、重新挂载后的读取，
以及原生文本范围在原无障碍对象释放后的读取。修补前这两个回归稳定失败，
包含与原报告相同的 `WinTextRangeProvider.GetText` 堆栈。
新增 Windows 属性回归先在原实现上复现 `30015 must not signal E_FAIL`，修补后检查 Culture、
ClassName 与未知编号均为 `VT_EMPTY`，已支持的控件类型保持正确；活动处理器异常继续传播，
释放后仍失败。Linux 不包含也不修改 `WinAccessible`。
JavaFX 25 的 GTK 实现没有创建原生 `Accessible` 的实现，继承的
[`Application.createAccessible()`](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/java/com/sun/glass/ui/Application.java)
返回 `null`；Linux 的公共入口测试不能冒充原生 UIA 验证。
内部反射开放仅用于测试；生产代码不开放这些内部包。

升级 JavaFX 时必须重新检查上游源码及本机平台工件，不能仅改校验和放行。
当上游版本已处理这些生命周期与属性契约问题，先在原始模块上运行这些回归，再移除修补工具、
Maven 执行与开发启动覆盖路径，以及打包的修补标记检查。保留行为回归。
本轮两平台和原生运行结果见 [L8 循环记录](loop/log.md#l8-启动与原生无障碍专项2026-10-28)。
