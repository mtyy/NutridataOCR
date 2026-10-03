(function (root) {
    "use strict";
    const fixedIds = [3, 10, 42, 85, 2, 84];
    const rounded = value => Math.round((value + Number.EPSILON) * 1000) / 1000;
    const numeric = value => value === null || value === undefined || value === "" ? 0 : Number(String(value).replace(",", "."));
    const ensure = (condition, message) => { if (!condition) throw new Error(message); };
    // Angular 14+ stores only a view id in __ngContext__; capture its private id -> view map when a view registers.
    if (!root.__nutridataViewHook) {
        root.__nutridataViewHook = true;
        const set = Map.prototype.set;
        Map.prototype.set = function (key, value) {
            if (!root.__nutridataViews && typeof key === "number" && Array.isArray(value) && value[19] === key) {
                root.__nutridataViews = this;
            }
            return set.call(this, key, value);
        };
    }

    function reconcile(rows, confirmed) {
        const byId = new Map(rows.map(row => [row.id, row]));
        const amounts = new Map(rows.map(row => [row.id, row.amount == null ? null : numeric(row.amount)]));
        const locked = new Set(fixedIds);
        const get = id => amounts.get(id) || 0;
        const set = (id, value) => {
            ensure(byId.has(id), "The website nutrient table has changed. Nothing was filled.");
            amounts.set(id, rounded(value));
        };
        ensure(Object.keys(confirmed).length === 6, "Review all six label values.");
        fixedIds.forEach(id => {
            const value = confirmed[id];
            ensure(typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 100,
                "Review all six numeric label values per 100 g.");
            set(id, value);
        });
        ensure(get(10) <= get(3), "Saturated fat exceeds confirmed fat.");
        ensure(get(85) <= get(42), "Sugars exceed confirmed carbohydrates.");
        ensure([3, 42, 2, 84].reduce((sum, id) => sum + get(id), 0) <= 100.000001,
            "Confirmed macros and salt exceed 100 g.");
        const children = id => byId.get(id).subComponents || [];
        const weightedSum = links => links.reduce((sum, link) => sum + get(link.childId) * link.weight, 0);
        function fit(links, maximum) {
            const fixed = weightedSum(links.filter(link => locked.has(link.childId)));
            ensure(fixed <= maximum + 0.000001, "The confirmed values conflict with the nutrient table.");
            const adjustable = links.filter(link => !locked.has(link.childId));
            const total = weightedSum(adjustable);
            if (total > maximum - fixed + 0.000001) {
                const ratio = Math.max(0, maximum - fixed) / total;
                adjustable.forEach(link => set(link.childId, Math.floor(get(link.childId) * ratio * 1000) / 1000));
            }
        }
        const derive = () => {
            set(29, get(84) * 400);
            set(48, get(42) - get(85) - get(86));
            set(82, weightedSum(children(82)));
            set(4, get(42) + get(17));
        };
        fit(children(82), get(3));
        fit(children(10), get(10));
        fit(children(12), get(12));
        fit(children(85), get(85));
        set(86, Math.min(get(86), get(42) - get(85)));
        fit(children(86), get(86));
        fit([17, 5].map(childId => ({ childId, weight: 1 })),
            100 - [3, 42, 2, 84].reduce((sum, id) => sum + get(id), 0));
        derive();
        for (let pass = 0; pass < 6; pass++) {
            rows.forEach(row => {
                const links = row.subComponents || [];
                if (!links.length || links.some(link => link.weight <= 0) || [39, 40].includes(row.id)) return;
                if (weightedSum(links) <= get(row.id) + 0.000001) return;
                const fixed = weightedSum(links.filter(link => locked.has(link.childId)));
                if (fixed > get(row.id) && !locked.has(row.id)) set(row.id, fixed);
                fit(links, get(row.id));
            });
            derive();
        }
        [39, 40].forEach(id => set(id, weightedSum(children(id))));
        rows.forEach(row => {
            const value = amounts.get(row.id);
            ensure(value === null || (Number.isFinite(value) && value >= 0), "An inherited nutrient could not be reconciled.");
            const links = row.subComponents || [];
            if (links.length && links.every(link => link.weight > 0)) {
                ensure(weightedSum(links) <= get(row.id) + 0.001, "An inherited nutrient total could not be reconciled.");
            }
        });
        fixedIds.forEach(id => ensure(Math.abs(get(id) - confirmed[id]) < 0.000001, "A confirmed value changed."));
        return amounts;
    }

    function editorContext() {
        ensure(location.origin === "https://tap.nutridata.ee", "Open NutriData before filling.");
        const modals = document.querySelectorAll("app-foodstuff-modal");
        ensure(modals.length === 1, "Open a new food draft and choose a similar base food before scanning.");
        const modal = modals[0];
        const context = modal.__ngContext__;
        const view = Array.isArray(context) ? context : root.__nutridataViews?.get(context);
        const editor = Array.isArray(view) && view.find(value =>
            value && typeof value.changeInput === "function" && typeof value.startCalculation === "function" &&
            typeof value.validateComponents === "function");
        ensure(editor && Array.isArray(editor.components) && Array.isArray(editor.temp),
            "Could not reach the food editor. Close and reopen the food dialog, then try again.");
        ensure(editor.components.length >= 40 && editor.components.length <= 200 &&
            new Set(editor.components.map(row => row.id)).size === editor.components.length,
            "The website nutrient table has changed.");
        const inputs = fixedIds.map(id => {
            const row = editor.components.find(component => component.id === id);
            ensure(row && row.unit === "UNIT_GRAM" && editor.temp.includes(row), "The website nutrient bindings have changed.");
            const matches = [...modal.querySelectorAll("tbody tr")].filter(element =>
                element.querySelector("td")?.textContent.trim().startsWith(row.name));
            ensure(matches.length === 1, "Open the package-data tab before scanning.");
            const input = matches[0].querySelector("input:not([disabled])");
            ensure(input, "A label field is not editable.");
            return { id, input };
        });
        return { modal, editor, inputs };
    }

    function run(command, token, payload) {
        try {
            const { editor, inputs } = editorContext();
            if (command === "capture") return { ok: true };
            ensure(command === "apply", "Unknown command.");
            const plan = reconcile(editor.components, payload.amounts);
            const saved = editor.components.map(row => ({ row, amount: row.amount, originalAmount: row.originalAmount,
                methodId: row.methodId, touched: row.touched, class: row.class }));
            const pristine = editor.pristine;
            const energy = editor.energyCalculated;
            const energyKj = editor.kJenergyCalculated;
            const changes = saved.filter(state => !fixedIds.includes(state.row.id) &&
                Math.abs(numeric(state.amount) - numeric(plan.get(state.row.id))) > 0.000001);
            const refreshInputs = () => inputs.forEach(({ id, input }) => {
                input.value = String(editor.components.find(row => row.id === id).amount);
                input.dispatchEvent(new Event("input", { bubbles: true }));
            });
            try {
                saved.forEach(state => {
                    const row = state.row;
                    row.amount = plan.get(row.id);
                    row.originalAmount = row.amount;
                    row.methodId = [39, 40].includes(row.id) ? 4 : 12;
                    if (fixedIds.includes(row.id) || changes.includes(state)) row.touched = true;
                });
                refreshInputs();
                inputs.forEach(({ input }) => input.dispatchEvent(new Event("change", { bubbles: true })));
                saved.forEach(state => {
                    if (!fixedIds.includes(state.row.id)) state.row.methodId = state.methodId;
                });
                editor.pristine = false;
                editor.startCalculation(null);
                editor.validateComponents(editor.temp);
                fixedIds.forEach(id => ensure(Math.abs(numeric(editor.components.find(row => row.id === id).amount) -
                    payload.amounts[id]) < 0.000001, "NutriData changed a confirmed label value."));
                ensure(!editor.temp.some(row => String(row.class || "").startsWith("error")),
                    "NutriData still reports a nutrient conflict.");
                const actual = reconcile(editor.components, payload.amounts);
                editor.components.forEach(row => {
                    if (![39, 40].includes(row.id)) ensure(Math.abs(numeric(actual.get(row.id)) - numeric(row.amount)) < 0.001,
                        "NutriData changed a reconciled value.");
                });
                inputs.forEach(({ id, input }) => ensure(Math.abs(numeric(input.value) - payload.amounts[id]) < 0.000001,
                    "A visible label field did not update."));
                return { ok: true, changes: changes.map(state => ({ name: state.row.name, unit: state.row.unit,
                    before: state.amount, after: state.row.amount })), kcal: editor.energyCalculated };
            } catch (error) {
                saved.forEach(({ row, ...state }) => Object.assign(row, state));
                editor.energyCalculated = energy;
                editor.kJenergyCalculated = energyKj;
                editor.pristine = pristine;
                refreshInputs();
                throw new Error(error.message + " Original nutrient values restored; nothing was saved.");
            }
        } catch (error) {
            return { ok: false, error: error.message || "Could not fill this food draft." };
        }
    }
    root.NutridataDraft = { run, reconcile };
    if (typeof module !== "undefined") module.exports = root.NutridataDraft;
})(globalThis);