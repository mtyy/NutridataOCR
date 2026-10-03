const assert = require("node:assert/strict");
const { reconcile, run } = require("../../main/assets/nutridata-draft.js");

const fixed = { 3: 1, 10: 0.2, 42: 6, 85: 0.1, 2: 4, 84: 1.2 };
const definitions = [
    [3, 2.5, [[82, 1]]], [10, 0.3, [[44, 1], [45, 1]]], [42, 12.9, [[48, 1], [85, 1], [86, 1]]],
    [85, 0.4, [[49, 1]]], [2, 7.5], [84, 0.532, [[29, 0.0025]]], [29, 213, [[84, 400]]],
    [82, 2.1, [[10, 1], [11, 1], [12, 1], [79, 1]]], [11, 0.7], [12, 1.1, [[46, 1], [47, 1]]],
    [79, 0], [44, 0.2], [45, 0], [46, 1], [47, 0.1], [49, 0.4], [86, 0, [[93, 1]]], [93, 0],
    [48, 12.5, [[42, 1], [85, -1], [86, -1]]], [17, 6.2], [5, 0], [4, 19.1, [[42, 1], [17, 1]]],
    [39, 117, [[3, 9], [42, 4], [2, 4], [17, 2], [5, 7], [86, -1.6], [93, -2.4]]],
    [40, 488.9, [[3, 37], [42, 17], [2, 17], [17, 8], [5, 29], [86, -7], [93, -10]]],
];
const rows = definitions.map(([id, amount, links = []]) => ({
    id, amount, name: `Nutrient ${id}:`, unit: "UNIT_GRAM", methodId: 7,
    subComponents: links.map(([childId, weight]) => ({ childId, weight })),
}));
const before = JSON.stringify(rows);
for (const values of [fixed, { 3: 0, 10: 0, 42: 0, 85: 0, 2: 0, 84: 0 }]) {
    const result = reconcile(rows, values);
    Object.entries(values).forEach(([id, amount]) => assert.equal(result.get(Number(id)), amount));
    assert.equal(result.get(29), values[84] * 400);
    assert.equal(result.get(48), values[42] - values[85]);
    assert.ok(result.get(82) <= values[3]);
    assert.ok(result.get(49) <= values[85]);
}
assert.equal(JSON.stringify(rows), before);
assert.throws(() => reconcile(rows, { ...fixed, 10: 2 }), /Saturated/);
assert.throws(() => reconcile(rows, { ...fixed, 85: 7 }), /Sugars/);
assert.throws(() => reconcile(rows, { ...fixed, 2: 99 }), /100 g/);

while (rows.length < 40) rows.push({ id: 200 + rows.length, name: "Other", amount: null, methodId: 7, subComponents: [] });
const editor = {
    behaviour: "add", recipeComponents: [{}], components: rows, temp: rows,
    foodstuffForm: { value: { name: "New food" } }, pristine: true,
    changeInput() {}, validateComponents() {},
    startCalculation() {
        this.energyCalculated = Math.round(reconcile(this.components, fixed).get(39));
        if (this.fail) this.components.find(row => row.id === 3).amount = 99;
    },
};
const inputRows = Object.keys(fixed).map(key => {
    const row = rows.find(item => item.id === Number(key));
    const input = {
        value: String(row.amount),
        dispatchEvent(event) { if (event.type === "input") row.amount = Number(this.value); },
    };
    return { querySelector: selector => selector === "td" ? { textContent: row.name } : input };
});
const modal = { __ngContext__: [editor], querySelectorAll: () => inputRows };
global.location = { origin: "https://tap.nutridata.ee" };
global.document = { querySelectorAll: () => [modal] };
assert.equal(run("capture", "scan").ok, true);
editor.fail = true;
const originalAmounts = rows.map(row => row.amount);
assert.match(run("apply", "scan", { amounts: fixed }).error, /restored/);
assert.deepEqual(rows.map(row => row.amount), originalAmounts);
editor.fail = false;
// Angular 20 shape: numeric __ngContext__ resolved through the hooked view registry.
modal.__ngContext__ = 6705;
assert.equal(run("capture", "scan").ok, false);
const view = Array(21).fill(null);
view[19] = 6705;
view[20] = editor;
new Map().set(6705, view);
assert.equal(run("apply", "scan", { amounts: fixed }).ok, true);
Object.entries(fixed).forEach(([id, amount]) => assert.equal(rows.find(row => row.id === Number(id)).amount, amount));
global.location.origin = "https://example.com";
assert.equal(run("capture", "scan").ok, false);
console.log("Reconciliation, editor lookup, read-back and rollback passed.");