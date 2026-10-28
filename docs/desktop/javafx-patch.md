# JavaFX 无障碍生命周期修补

## 原因与范围

Windows UI Automation 的客户端可以保留文本范围，而原控件已经离开场景。
JavaFX 25.0.5 的 `Accessible.dispose()` 会清空事件处理器；保留的
`WinTextRangeProvider.GetText()` 随后仍会进入 `Accessible.getAttribute()`，导致空指针异常。
仅关闭窗口、关闭无障碍或捕获并忽略异常，都不能修复这个生命周期问题。

本项目仅对 `Accessible` 的两个入口增加释放保护，等价源码如下：

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
- 测试、开发启动与 jlink 打包都使用修补模块。打包额外核验修补标记及类文件校验和；
  保留 AOT 训练与实际缓存使用检查。

## 回归与移除条件

`AccessibilityLifecycleTest` 在两平台检查释放后查询与操作安全，且活动处理器的异常继续传播。
Windows 另检查真实场景移除后的查询、过期编辑无效、重新挂载后的读取，
以及原生文本范围在原无障碍对象释放后的读取。修补前这两个回归稳定失败，
包含与原报告相同的 `WinTextRangeProvider.GetText` 堆栈。
JavaFX 25 的 GTK 实现没有创建原生 `Accessible` 的实现，继承的
[`Application.createAccessible()`](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/java/com/sun/glass/ui/Application.java)
返回 `null`；Linux 的公共入口测试不能冒充原生 UIA 验证。
内部反射开放仅用于测试；生产代码不开放这些内部包。

升级 JavaFX 时必须重新检查上游源码及本机平台工件，不能仅改校验和放行。
当上游版本已处理同一生命周期问题，先在原始模块上运行这些回归，再移除修补工具、
Maven 执行与开发启动覆盖路径，以及打包的修补标记检查。保留行为回归。
本轮两平台和原生运行结果见 [L8 循环记录](loop/log.md#l8-启动与原生无障碍专项2026-10-28)。
