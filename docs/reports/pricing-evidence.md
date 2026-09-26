# 云费用核验依据（2026-09-19）

面向东京与新加坡，比较每月运行 60 小时与 730 小时。云平台结论、完整表格和可调计算器见同目录的
[项目评估报告](evaluation-report.html)。以下记录原始价格口径与复核方法；不把缺失价格填成零。

## 口径与推荐的适用范围

- Linux、约 8 GB 内存、单机低并发；磁盘至少 64 GB，公网总出站 100 GB/月（包含备份上传）。
- 所有持久资源保留整月。730 是年度平均月小时数，实际月份为 672–744 小时。
- 外部备份假设 20 GB-month，全部历史版本合计，R2 Standard 账户免费量未被其他项目使用，请求未超免费量。
- 不计新用户赠金、首购促销、承诺折扣、税费、汇兑费用、人工维护、付费支持和额外网络产品。
- 这是预算比较，不是性能基准。不同厂商的共享核、vCPU、OCPU、积分、带宽和磁盘性能不能等同。
- 单机 Compose 没有高可用保障；8 GB 是规划起点，必须用用户的实际数据验收。4 GB 只能作为进一步实测压缩成本的选项。

按当前可核验公开价，常开优先评估腾讯云国际站 Lighthouse 入门型 2 核 / 8 GB / 80 GB，
每月 $10 + 示例备份 $0.15。更大本地盘可比较 120 GB 通用型 $14.50/月。东京与新加坡同档价，
地域取决于数据源和访问者的网络实测，不能仅凭价格断言东京或新加坡更快。

若每月仅运行 60 小时，可评估 OCI A1 2 OCPU / 8 GB，按没有免费额度计算为 $4.79/月；
满 730 小时为 $26.23/月。ARM 实机验收与库存是前提。免费额度满足时可能更低，但不作为唯一可用方案。

## 算式与边界

| 项目 | 算式 | 结果 |
| --- | --- | --- |
| OCI A1 小时算力 | 2 × 0.01 + 8 × 0.0015 | $0.032/h |
| OCI 64 GB Balanced 磁盘 | 64 × (0.0255 + 10 × 0.0017) | $2.72/月 |
| 20 GB-month R2 Standard | ceil(max(20 − 10, 0)) × 0.015 | $0.15/月 |
| OCI 按需场景 | 60 × 0.032 + 2.72 + 0.15 | $4.79/月 |
| OCI 常开场景 | 730 × 0.032 + 2.72 + 0.15 | $26.23/月 |
| 腾讯云入门型常开或关机保留 | 10 + 0.15 | $10.15/月 |
| Lightsail IPv4 8 GB 保留整月 | 44 + 0.15 | $44.15/月 |
| OCI 与腾讯云基础费用交点 | (10 − 2.72) / 0.032 | 227.5 h/月 |

交点仅在相同任务时长、无其他费用、没有免费额度时成立。测试用例使用定点整数金额，
只在显示时四舍五入到美分。请求费用先扣免费量，再按厂商完整计费单位向上取整。
若备份占用量、请求数或流量超出假设，应重算；不能用这个简化计算器代替真实账单。

## 官方公开价来源

| 平台 | 核验内容与当前值 | 直接来源 |
| --- | --- | --- |
| 腾讯云国际站 | 东京/新加坡入门型 2 核 8 GB 80 GB 为 $10/月；30 Mbps，2,560 GB/月。通用型 120 GB 为 $14.50；套餐外东京 $0.13/GB，新加坡 $0.081/GB。文档最后更新 2025-06-23，本次重新访问；公开价不等于已确认账户可售库存。 | [价格详情](https://www-sg.tencentcloud.com/zh/document/product/1103/47794)、[计费概述](https://www.tencentcloud.com/document/product/1103/41403) |
| OCI 算力 | A1 $0.01/OCPU-hour + $0.0015/GB-hour，各地域同价。A1 的 OCPU 与 x86 的 vCPU 不应直接作性能等价换算。 | [A1 官方价格](https://www.oracle.com/cloud/compute/arm/) |
| OCI 磁盘 | SKU B91961 容量 $0.0255/GB-month；B91962 性能 $0.0017/VPU/GB-month。官方 API 更新时间 2026-09-09。默认 Balanced 为 10 VPU，两个费用相加。 | [容量 SKU](https://apexapps.oracle.com/pls/apex/cetools/api/v1/products/?partNumber=B91961&currencyCode=USD)、[性能 SKU](https://apexapps.oracle.com/pls/apex/cetools/api/v1/products/?partNumber=B91962&currencyCode=USD)、[价目表](https://www.oracle.com/cloud/price-list/) |
| OCI 免费额度 | Always Free 文档为 1,500 OCPU-hour / 9,000 GB-hour 与主区域 200 GB 块存储；付费租户价目说明另列 3,000 / 18,000，不能混为统一额度。空闲实例可能回收，容量可能不足。 | [免费资源及条件](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[付费租户价目脚注](https://www.oracle.com/cloud/price-list/) |
| OCI 网络 | 亚太、日本每月前 10 TB 出站免费；普通公网 IP 不另列费用，实际账户若启用其他网络产品则单独计费。 | [网络价目](https://www.oracle.com/cloud/price-list/)、[公网地址发布说明](https://blogs.oracle.com/cloud-infrastructure/post/release-announcement-reserved-public-ips-are-now-available-on-oracle-cloud-infrastructure) |
| AWS Lightsail | Linux IPv4 8 GB 为 $44/月，160 GB SSD、5 TB 流量；IPv6-only 为 $40。4 GB 的 $24 不应用来与 8 GB 方案比较。 | [套餐价格](https://aws.amazon.com/lightsail/pricing/)、[流量与地域规则](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html) |
| EC2 Linux t4g.large | 东京 $0.0864/h、新加坡 $0.0848/h，2 vCPU / 8 GiB。官方组件数据发布时间 2026-09-18T20:33:44Z。未含 EBS、公网 IP、出站、备份、突发 CPU 积分。 | [定价页](https://aws.amazon.com/ec2/pricing/on-demand/)、[东京原始价目](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20%28Tokyo%29/Linux/index.json)、[新加坡原始价目](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20%28Singapore%29/Linux/index.json) |
| GCP e2-standard-2 | 东京 $0.08596572/h、新加坡 $0.08266614/h，2 vCPU / 8 GiB。分别乘 730 得 $62.7549756 / $60.3462822，仅计算费。已核对页面对应地域的数据表，未使用页面默认美国价。 | [官方区域价格表](https://cloud.google.com/products/compute/pricing/general-purpose) |
| Azure Standard_B2as_v2 | Japan East $0.098/h，Southeast Asia $0.0944/h；Linux Consumption 非 Spot。E6 LRS 64 GiB 磁盘基础费均 $4.80/月；磁盘操作、共享挂载（如启用）、公网地址、出站、备份另计。 | [零售 API 说明](https://learn.microsoft.com/en-us/rest/api/cost-management/retail-prices/azure-retail-prices)、[规格](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/general-purpose/basv2-series) |
| Vultr | 官方公开 API `vc2-4c-8gb`：4 vCPU、8,192 MB、160 GB SSD、4,096 GB 流量，$40/月封顶，$0.055/h；可选地区包含 `nrt`、`sgp`。 | [公开套餐 API](https://api.vultr.com/v2/plans)、[价格页](https://www.vultr.com/pricing/) |
| DigitalOcean | Basic Regular 8 GiB / 4 vCPU / 160 GiB / 5,000 GiB，$48/月封顶，$0.07143/h。按秒计费有最低计费单位；新加坡可选，东京未列于当前地区表。 | [价格](https://www.digitalocean.com/pricing/droplets)、[地区](https://docs.digitalocean.com/platform/regional-availability/) |
| Akamai Linode | Shared 8 GB / 4 vCPU / 160 GB / 5 TB，$48/月封顶，$0.072/h；东京与新加坡可选。 | [价格](https://www.akamai.com/cloud/pricing)、[地区](https://www.akamai.com/why-akamai/global-infrastructure/availability) |
| Hetzner | 新加坡 CPX32 调价后为 $57.99/月封顶或 $0.0929/h，未含 IPv4/VAT。2026-06-15 生效的价格表优先于旧评测。新加坡存在，不能沿用“只有欧洲和美国”的旧说法。 | [调价表](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)、[地区](https://www.hetzner.com/cloud/) |
| 阿里云 | 轻量公开页仍展示 8 GB $35/月参考；2026-04-20 部分旧 SKU 停售。必须核对当前 ListPlans/购买页；本轮没有账户级现售完整报价，未将参考数列为可保证购买的方案。ECS 另按计算、盘、IP、流量拆分。 | [公开套餐](https://www.alibabacloud.com/en/product/swas/pricing?_p_lc=1)、[停售公告](https://www.alibabacloud.com/help/en/simple-application-server/product-overview/announcement-on-end-of-sale-of-some-sas-instance-types)、[计费项目](https://www.alibabacloud.com/help/en/simple-application-server/product-overview/billable-items) |
| 华为云 | Flexus L 套餐与 Flexus X 按需应分开；新加坡实际规格及单价需购买页报价。未拿历史 PDF 图片价格作当前报价，东京 Flexus L 未确认可售。 | [官方计费入口](https://www.huaweicloud.com/intl/en-us/product/flexus/pricing.html) |
| Cloudflare R2 | Standard $0.015/GB-month，Class A $4.50/百万、Class B $0.36/百万；免费存储 10 GB-month、A 100 万、B 1,000 万，超出按完整单位取整。免费出站仅指 R2 端；IA 层另有取回费和最短保留时间。 | [定价与取整示例](https://developers.cloudflare.com/r2/pricing/) |

### Azure 可复现查询口径

端点：`https://prices.azure.com/api/retail/prices`，币种 USD。按地区分别过滤：

```text
armRegionName eq 'japaneast' and armSkuName eq 'Standard_B2as_v2' and priceType eq 'Consumption'
armRegionName eq 'southeastasia' and armSkuName eq 'Standard_B2as_v2' and priceType eq 'Consumption'
```

结果进一步选 `productName = Virtual Machines Basv2 Series`，普通 Linux meter，排除 Cloud Services、
Windows、Spot、Low Priority、Reservation；不得把混合返回项的第一个价格当作目标值。
磁盘筛选 `serviceName = Storage`、同一区域、`productName = Standard SSD Managed Disks`、
`meterName = E6 LRS Disk`。`Disk Mount` 是另一计费项，不混入单盘基本费；`Disk Operations` 也不假定免费。

### 额外费用采购表

采购前填写实际单价和数量：算力及最低计费单位、系统盘和数据盘、额外 IOPS/吞吐、快照/备份所有版本、
公网 IPv4 保留时长、出站目的地与账户免费量、NAT/负载均衡、监控日志保留、跨区传输、域名、税和支持套餐。
没有实际负载与目的地，EC2/GCP/Azure 的这些项目无法得到唯一完整账单；报告保留算力小计，不伪造总价。

## 停机后的计费证据

- [OCI Standard 停止暂停算力费](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/resource-billing-stopped-instances.htm)。磁盘按保留容量继续计费。
- [AWS Lightsail 保留实例计费](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html)；[EC2 按需及 CPU 积分](https://aws.amazon.com/ec2/pricing/on-demand/)、[公网 IPv4](https://aws.amazon.com/vpc/pricing/)。
- [GCP stop / suspend 区别](https://docs.cloud.google.com/compute/docs/instances/suspend-stop-reset-instances-overview)。休眠、保留 Local SSD 和普通 stop 不应混算。
- [Azure 状态与计费](https://learn.microsoft.com/en-us/azure/virtual-machines/states-billing)：Stopped 与 Deallocated 不同。
- [Vultr](https://docs.vultr.com/support/platform/billing/are-stopped-instances-still-billed-on-vultr)、[DigitalOcean](https://docs.digitalocean.com/products/droplets/details/pricing/)、[Akamai](https://techdocs.akamai.com/cloud-computing/docs/understanding-how-billing-works)、[Hetzner](https://docs.hetzner.com/cloud/billing/faq/)：关机保留仍收费。

## 托管 Airflow 与 Cloudflare 的边界

[MWAA 定价](https://aws.amazon.com/managed-workflows-for-apache-airflow/pricing/)现在区分传统常驻环境与 Serverless。
传统环境示例中的美国 $0.49/小时不是东京报价，也不能代表 Serverless 最低费用；Serverless 的托管任务还需
核对操作符、区域及本项目 JVM/Spark 执行方式是否兼容。[Google 托管 Airflow](https://cloud.google.com/products/managed-service-for-apache-airflow/pricing)
按代际及区域计价，不只计算 DAG 执行时间。当前没有需求证明迁移有经济收益。

[Cloudflare Containers 架构](https://developers.cloudflare.com/containers/concepts/architecture/)不等于现有持久化
Compose 的直接托管环境；Workers/Pages 不能直接承载这些进程。Tunnel 负责访问路径，不能创造免费出站路由。
R2 当前只作为备份候选，项目没有加入 S3A 连接器，不承诺 Spark 可直接读写 R2。

## 复核与维护

从仓库根目录运行：

```bash
node --test tests/docs/cloud-costs.test.cjs
python3 -B -m unittest discover -s tests/deploy -v
```

评估报告的计算器与测试共享 [assets/cloud-costs.js](assets/cloud-costs.js) 的快照数据与计费函数。
每次变更价格必须同时复核地域、规格、币种、账户适用条件、停机规则和附加费，并更新本页核验日期。
不保存完整抓取页面、账户报价、凭据或测试缓存到仓库。
