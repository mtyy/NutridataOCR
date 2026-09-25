(function (root) {
    "use strict";
    if (root.location.origin !== "https://tap.nutridata.ee" || root.__nutridataSearchInstalled) return;
    root.__nutridataSearchInstalled = true;

    /*
     * Website adapter, injected by MainActivity when a WebView page finishes loading.
     * Goal: carry the "Lisa toit" / "Otsi" query through "Lisa oma toiduaine" into
     * the empty search opened by "Vota aluseks sarnane toit".
     * Verified 2026-09-25: the original search stays mounted, followed in DOM order
     * by the food editor and similar-food search in separate ngb-modal-window nodes.
     * Both searches use searchQuery; a bubbling input event updates Angular's model.
     * If the site changes, inspect that unsaved flow and update the selectors,
     * source/target association and input notification here. Missing matches must
     * do nothing; do not broaden matching to unrelated inputs. Never select food,
     * submit/save a form or change diary entries, including during verification.
     * Preserve the rules below: page-memory only, fill an empty target once, and
     * leave existing text, later edits and clearing alone. Offline regression:
     * node app/src/test/js/nutridata-search.test.cjs
     * Its DOM is mocked; separately verify this adapter against the changed site.
     */
    const siteAdapter = {
        searchSelector: 'tois-app-add-food-modal input[formcontrolname="searchQuery"]',

        isSearchInput(input) {
            return input.matches(this.searchSelector);
        },

        searchFields() {
            const fields = [];
            let source = null;
            let draftOpen = false;
            for (const modal of root.document.querySelectorAll("ngb-modal-window")) {
                if (modal.querySelector("app-foodstuff-modal")) {
                    draftOpen = true;
                    continue;
                }
                const input = modal.querySelector(this.searchSelector);
                if (!input) continue;
                fields.push({ input, source: draftOpen ? source : null });
                if (!draftOpen) source = input;
            }
            return fields;
        },

        setQuery(input, query) {
            input.value = query;
            input.dispatchEvent(new root.Event("input", { bubbles: true }));
        },
    };

    const queries = new WeakMap();
    const initialized = new WeakSet();

    root.document.addEventListener("input", event => {
        if (siteAdapter.isSearchInput(event.target)) queries.set(event.target, event.target.value);
    }, true);

    function prefillSearch() {
        for (const { input, source } of siteAdapter.searchFields()) {
            if (!queries.has(input)) queries.set(input, input.value);
            if (!source || initialized.has(input)) continue;
            initialized.add(input);
            const query = queries.get(source);
            if (input.value !== "" || !query.trim()) continue;
            siteAdapter.setQuery(input, query);
        }
    }

    const observer = new root.MutationObserver(prefillSearch);
    observer.observe(root.document.documentElement, { childList: true, subtree: true });
    prefillSearch();
})(globalThis);