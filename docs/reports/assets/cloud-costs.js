/* Public list-price snapshot, verified 2026-09-19. USD before tax; no promotional credits.
   Shared by the calculator in ../evaluation-report.html (global CloudCosts) and by
   tests/docs/cloud-costs.test.cjs (CommonJS export). The page owns all DOM wiring. */
"use strict";

const CloudCosts = (() => {
  // Decimal arithmetic in 10^-10 USD; never accumulate money in binary floating point.
  const scale = 10000000000n;
  function decimal(value) {
    if (typeof value !== "string" || !/^\d+(?:\.\d{1,10})?$/.test(value)) {
      throw new RangeError("请输入有效的非负十进制数，最多 10 位小数。");
    }
    const [whole, fraction = ""] = value.split(".");
    return BigInt(whole) * scale + BigInt(fraction.padEnd(10, "0"));
  }
  const usd = (value) => {
    const cents = (value + 50000000n) / 100000000n;
    return `$${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
  };
  const rates = Object.freeze({
    tencent: { name: "腾讯云 Lighthouse 入门型", monthly: "10", disk: "0", complete: true },
    tencentGeneral: { name: "腾讯云 Lighthouse 通用型", monthly: "14.50", disk: "0", complete: true },
    oracle: { name: "OCI A1：2 OCPU / 8 GB（无免费额度）", hourly: "0.032", disk: "2.72", complete: true },
    lightsail: { name: "AWS Lightsail：8 GB / IPv4", monthly: "44", disk: "0", complete: true },
    vultr: { name: "Vultr vc2-4c-8gb", monthly: "40", disk: "0", complete: true },
    digitalocean: { name: "DigitalOcean Basic：8 GB", monthly: "48", disk: "0", complete: true, singaporeOnly: true },
    linode: { name: "Akamai Linode：8 GB", monthly: "48", disk: "0", complete: true },
    hetzner: { name: "Hetzner CPX32 新加坡", monthly: "57.99", disk: "0", complete: false, singaporeOnly: true },
    awsTokyo: { name: "EC2 t4g.large 东京", hourly: "0.0864", disk: null, complete: false },
    awsSingapore: { name: "EC2 t4g.large 新加坡", hourly: "0.0848", disk: null, complete: false },
    gcpTokyo: { name: "GCP e2-standard-2 东京", hourly: "0.08596572", disk: null, complete: false },
    gcpSingapore: { name: "GCP e2-standard-2 新加坡", hourly: "0.08266614", disk: null, complete: false },
    azureTokyo: { name: "Azure B2as v2 东京", hourly: "0.098", disk: "4.80", complete: false },
    azureSingapore: { name: "Azure B2as v2 新加坡", hourly: "0.0944", disk: "4.80", complete: false },
  });
  function compute(id, hours) {
    const rate = rates[id];
    if (!rate) throw new RangeError("未知套餐。");
    const h = decimal(hours);
    if (h > decimal("744")) throw new RangeError("每月运行时长须在 0–744 小时之间。");
    // A retained monthly/bundled VM keeps billing even when powered off.
    return rate.monthly ? decimal(rate.monthly) : decimal(rate.hourly) * h / scale;
  }
  function backup(gbMonths, classA = "0", classB = "0") {
    const gb = decimal(gbMonths);
    if (gb > decimal("1000000")) throw new RangeError("备份容量超出此估算器范围。");
    const positive = (n) => n > 0n ? n : 0n;
    const ceil = (n, unit) => (n + unit - 1n) / unit;
    // R2 Standard account-wide allowances and rounding to billable GB / million requests.
    return ceil(positive(gb - decimal("10")), scale) * decimal("0.015")
      + ceil(positive(decimal(classA) - decimal("1000000")), decimal("1000000")) * decimal("4.50")
      + ceil(positive(decimal(classB) - decimal("10000000")), decimal("1000000")) * decimal("0.36");
  }
  function total(id, hours, backupGb) {
    const rate = rates[id];
    if (!rate || !rate.complete || rate.disk === null) {
      throw new RangeError("该套餐仍有未核定附加费，不能显示为完整总价。");
    }
    return compute(id, hours) + decimal(rate.disk) + backup(backupGb);
  }
  // What is already known to be billed: equals total() for complete plans and is only a lower
  // bound for the others, whose unverified extras are left out rather than counted as zero.
  function lowerBound(id, hours, backupGb) {
    const rate = rates[id];
    if (!rate) throw new RangeError("未知套餐。");
    return compute(id, hours) + decimal(rate.disk ?? "0") + backup(backupGb);
  }
  return Object.freeze({ rates, decimal, usd, compute, backup, total, lowerBound });
})();

if (typeof module !== "undefined" && module.exports) module.exports = CloudCosts;
