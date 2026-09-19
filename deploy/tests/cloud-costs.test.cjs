"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const costs = require("../../docs/deployment/cloud-costs.js");

test("retained monthly instances do not become cheaper after shutdown", () => {
  for (const id of ["tencent", "lightsail", "vultr", "digitalocean", "linode", "hetzner"]) {
    assert.equal(costs.compute(id, "0"), costs.compute(id, "730"));
  }
});
test("on-demand totals retain the whole month's persistent disk", () => {
  assert.equal(costs.usd(costs.total("oracle", "60", "20")), "$4.79");
  assert.equal(costs.usd(costs.total("oracle", "730", "20")), "$26.23");
  assert.equal(costs.usd(costs.total("oracle", "0", "20")), "$2.87");
  assert.equal(costs.usd(costs.total("tencent", "60", "20")), "$10.15");
  assert.equal(costs.usd(costs.total("lightsail", "730", "20")), "$44.15");
});
test("regional prices and decimal rounding are exact", () => {
  assert.equal(costs.usd(costs.compute("gcpTokyo", "730")), "$62.75");
  assert.equal(costs.usd(costs.compute("gcpSingapore", "730")), "$60.35");
  assert.equal(costs.usd(costs.compute("awsTokyo", "730")), "$63.07");
  assert.equal(costs.usd(costs.compute("azureSingapore", "60")), "$5.66");
  assert.equal(costs.usd(costs.decimal("0.005")), "$0.01");
});
test("R2 free thresholds and billable-unit rounding", () => {
  assert.equal(costs.backup("10", "1000000", "10000000"), 0n);
  assert.equal(costs.backup("10.01"), costs.decimal("0.015"));
  assert.equal(costs.backup("10", "1000001", "10000001"), costs.decimal("4.86"));
});
test("unverified extras and invalid usage never produce misleading totals", () => {
  for (const id of ["awsTokyo", "gcpTokyo", "azureTokyo", "hetzner", "unknown"]) {
    assert.throws(() => costs.total(id, "60", "20"), RangeError);
  }
  for (const hours of ["", "-1", "NaN", "Infinity", "1e2", "745"]) {
    assert.throws(() => costs.compute("oracle", hours), RangeError);
  }
  assert.throws(() => costs.backup("-2"), RangeError);
});
