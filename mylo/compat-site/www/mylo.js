// Shared helpers: results are written into the page so the device test (and a person) can read them.
function show(id, text) { document.getElementById(id).textContent = text; }
function param(name) { return new URLSearchParams(location.search).get(name); }
