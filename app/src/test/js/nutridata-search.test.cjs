const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const script = fs.readFileSync(require.resolve("../../main/assets/nutridata-search.js"), "utf8");
const searchSelector = 'tois-app-add-food-modal input[formcontrolname="searchQuery"]';
let modals = [];
let onMutation;
let onInput;
let listenerCount = 0;
const root = {
    location: { origin: "https://tap.nutridata.ee" },
    Event,
    document: {
        documentElement: {},
        addEventListener(type, listener) {
            assert.equal(type, "input");
            onInput = listener;
            listenerCount++;
        },
        querySelectorAll(selector) {
            assert.equal(selector, "ngb-modal-window");
            return modals;
        },
    },
    MutationObserver: class {
        constructor(callback) { onMutation = callback; }
        observe() {}
    },
};
const context = vm.createContext(root);
function search(value = "") {
    const input = {
        value,
        modelValue: value,
        events: [],
        matches: selector => selector === searchSelector,
        dispatchEvent(event) {
            this.events.push(event.type);
            this.modelValue = this.value;
            onInput({ target: this });
        },
    };
    return { input, querySelector: selector => selector === searchSelector ? input : null };
}
const editor = { querySelector: selector => selector === "app-foodstuff-modal" ? {} : null };
const source = search();
modals = [source];
vm.runInContext(script, context);
source.input.value = 'Kaerahelbed "t\u00e4is"';
onInput({ target: source.input });
source.input.value = "";
const similar = search();
modals = [source, editor, similar];
onMutation();
assert.equal(similar.input.value, 'Kaerahelbed "t\u00e4is"');
assert.equal(similar.input.modelValue, similar.input.value);
assert.deepEqual(similar.input.events, ["input"]);
similar.input.value = "";
similar.input.dispatchEvent(new Event("input"));
onMutation();
assert.equal(similar.input.value, "");

const existing = search("My own search");
modals = [source, editor, existing];
onMutation();
assert.equal(existing.input.value, "My own search");
assert.deepEqual(existing.input.events, []);
const unrelated = search();
modals = [source, unrelated];
onMutation();
assert.equal(unrelated.input.value, "");
const fresh = search();
modals = [fresh, editor, unrelated];
onMutation();
assert.equal(unrelated.input.value, "");
const standalone = search();
modals = [editor, standalone];
onMutation();
assert.equal(standalone.input.value, "");
vm.runInContext(script, context);
assert.equal(listenerCount, 1);
vm.runInNewContext(script, { location: { origin: "https://example.com" } });
console.log("Query carry-over, input events, edits, fresh flows and origin guard passed.");