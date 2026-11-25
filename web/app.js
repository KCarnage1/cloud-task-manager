(function () {
  "use strict";
  const $ = s => document.querySelector(s);
  const $$ = s => [...document.querySelectorAll(s)];
  const STATUS = { todo: "Waiting", doing: "In progress", done: "Finished" };
  const PRIORITY = { low: "Low", normal: "Normal", high: "High" };
  const NEXT = { todo: ["doing", "Start"], doing: ["done", "Mark done"], done: ["todo", "Reopen"] };

  let tasks = [], mode = "login", activeCol = "todo", editingId = null, deleteArmed = false;

  async function api(method, path, body) {
    const opts = { method, headers: { "X-Requested-With": "taskmanager" } };
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    let res;
    try { res = await fetch(path, opts); } catch { return { ok: false, status: 0, data: { error: "Can't reach the server. Check your connection." } }; }
    let data = null;
    if (res.status !== 204) { try { data = await res.json(); } catch { data = {}; } }
    if (res.status === 401 && path !== "/api/login") showAuth();
    return { ok: res.ok, status: res.status, data };
  }

  const el = (tag, props = {}, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) k === "class" ? (e.className = v) : k === "text" ? (e.textContent = v) : e.setAttribute(k, v);
    e.append(...kids);
    return e;
  };

  /* ---------- sign in / sign up ---------- */
  function showAuth() { $("#app").hidden = true; $("#auth").hidden = false; $("#auth-form [name=username]").focus(); }
  function showApp(username) {
    $("#auth").hidden = true; $("#app").hidden = false; $("#who").textContent = username;
    loadTasks();
  }

  function setMode(m) {
    mode = m;
    $$(".tabs button").forEach(b => b.setAttribute("aria-pressed", b.dataset.mode === m));
    $("#auth-submit").textContent = m === "login" ? "Sign in" : "Create account";
    $("#pw-hint").hidden = m === "login";
    $("#auth-form [name=password]").autocomplete = m === "login" ? "current-password" : "new-password";
    clearErrors($("#auth-form")); $("#auth-error").textContent = "";
  }
  $$(".tabs button").forEach(b => b.addEventListener("click", () => setMode(b.dataset.mode)));
  $("#show-pw").addEventListener("change", e => { $("#auth-form [name=password]").type = e.target.checked ? "text" : "password"; });

  function clearErrors(form) { form.querySelectorAll(".err").forEach(x => (x.textContent = "")); form.querySelectorAll("[aria-invalid]").forEach(x => x.removeAttribute("aria-invalid")); }
  function showFieldErrors(form, errors) {
    let first = null;
    for (const [field, msg] of Object.entries(errors || {})) {
      const out = form.querySelector('.err[data-for="' + field + '"]'), input = form.querySelector('[name="' + field + '"]');
      if (out) out.textContent = msg;
      if (input) { input.setAttribute("aria-invalid", "true"); first = first || input; }
    }
    if (first) first.focus();
  }

  $("#auth-form").addEventListener("submit", async e => {
    e.preventDefault();
    const form = e.currentTarget, btn = $("#auth-submit"), out = $("#auth-error");
    clearErrors(form); out.textContent = "";
    const body = { username: form.username.value.trim(), password: form.password.value };
    if (!body.username || !body.password) { out.textContent = "Enter a username and a password."; return; }
    btn.disabled = true;
    const r = await api("POST", mode === "login" ? "/api/login" : "/api/register", body);
    btn.disabled = false;
    if (r.ok) { form.reset(); showApp(r.data.username); return; }
    if (r.data.errors) showFieldErrors(form, r.data.errors); else out.textContent = r.data.error || "Something went wrong.";
  });

  $("#logout").addEventListener("click", async () => { await api("POST", "/api/logout", {}); tasks = []; render(); setMode("login"); showAuth(); });

  /* ---------- tasks ---------- */
  async function loadTasks() {
    notice("");
    const r = await api("GET", "/api/tasks");
    if (r.ok) { tasks = r.data.tasks; render(); }
    else if (r.status !== 401) notice(r.data.error || "Couldn't load your tasks.", "Try again", loadTasks);
  }

  function notice(msg, actionLabel, action) {
    const n = $("#notice");
    n.hidden = !msg;
    n.replaceChildren();
    if (!msg) return;
    n.append(el("span", { text: msg }));
    if (action) { const b = el("button", { type: "button", text: actionLabel }); b.addEventListener("click", action); n.append(b); }
  }

  const priorityRank = { high: 0, normal: 1, low: 2 };
  const matches = t => {
    const q = $("#search").value.trim().toLowerCase(), f = $("#filter").value;
    return (!f || t.priority === f) && (!q || t.title.toLowerCase().includes(q) || (t.notes || "").toLowerCase().includes(q));
  };

  function dueText(t) {
    if (!t.due) return null;
    const d = new Date(t.due + "T00:00:00"), today = new Date(); today.setHours(0, 0, 0, 0);
    const label = d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" });
    return { label: "Due " + label, late: t.status !== "done" && d < today };
  }

  function card(t) {
    const li = el("li", { class: "task p-" + t.priority + (t.status === "done" ? " is-done" : ""), "data-id": t.id });
    const meta = el("p", { class: "meta" }, el("span", { class: "pri-" + t.priority, text: PRIORITY[t.priority] + " priority" }));
    const due = dueText(t);
    if (due) meta.append(el("span", { class: due.late ? "late" : "", text: due.late ? due.label + " (overdue)" : due.label }));
    li.append(el("h3", { text: t.title }), meta);
    if (t.notes) li.append(el("p", { class: "notes", text: t.notes }));
    const [next, label] = NEXT[t.status];
    const go = el("button", { type: "button", class: "go", text: label });
    go.append(el("span", { class: "sr-only", text: ": " + t.title }));
    go.addEventListener("click", () => move(t.id, next));
    const edit = el("button", { type: "button", text: "Edit" });
    edit.append(el("span", { class: "sr-only", text: " " + t.title }));
    edit.addEventListener("click", () => openEdit(t.id));
    const acts = el("div", { class: "acts" }, go);
    if (t.status === "doing") {
      const back = el("button", { type: "button", text: "Back to waiting" });
      back.addEventListener("click", () => move(t.id, "todo"));
      acts.append(back);
    }
    acts.append(edit);
    li.append(acts);
    return li;
  }

  function render() {
    const counts = { todo: 0, doing: 0, done: 0 };
    for (const status of Object.keys(STATUS)) {
      const list = $('[data-list="' + status + '"]');
      const items = tasks.filter(t => t.status === status && matches(t)).sort((a, b) =>
        priorityRank[a.priority] - priorityRank[b.priority] || (a.due || "9999").localeCompare(b.due || "9999") || b.id - a.id);
      counts[status] = items.length;
      list.replaceChildren(...(items.length ? items.map(card) : [el("li", { class: "empty", text: emptyText(status) })]));
      $('[data-count="' + status + '"]').textContent = items.length;
    }
    const tabs = $("#cols-tabs");
    tabs.replaceChildren(...Object.entries(STATUS).map(([s, name]) => {
      const b = el("button", { type: "button", "aria-pressed": String(s === activeCol), text: name + " (" + counts[s] + ")" });
      b.addEventListener("click", () => { activeCol = s; render(); });
      return b;
    }));
    $$(".col").forEach(c => c.classList.toggle("show", c.dataset.status === activeCol));
  }

  function emptyText(status) {
    if ($("#search").value.trim() || $("#filter").value) return "No tasks match.";
    return { todo: "Nothing waiting. Add a task above.", doing: "Nothing in progress. Start one from Waiting.", done: "Nothing finished yet." }[status];
  }

  /* An optimistic move: the card jumps columns at once and goes back, with a message, if the server says no. */
  async function move(id, status) {
    const t = tasks.find(x => x.id === id), before = t.status;
    t.status = status; render();
    const r = await api("PATCH", "/api/tasks/" + id, { status });
    if (r.ok) { Object.assign(t, r.data); render(); notice(""); }
    else { t.status = before; render(); if (r.status !== 401) notice(r.data.error || "That change didn't save.", "Try again", () => move(id, status)); }
  }

  $("#add").addEventListener("submit", async e => {
    e.preventDefault();
    const f = e.currentTarget, out = $("#add-error");
    out.textContent = "";
    const title = f.elements.title.value.trim();
    if (!title) { out.textContent = "Give the task a title."; f.elements.title.focus(); return; }
    const r = await api("POST", "/api/tasks", { title, priority: f.priority.value, due: f.due.value || null });
    if (r.ok) { tasks.unshift(r.data); f.elements.title.value = ""; f.due.value = ""; render(); f.elements.title.focus(); }
    else out.textContent = r.data.errors ? Object.values(r.data.errors).join(" ") : r.data.error || "Couldn't add that task.";
  });

  $("#search").addEventListener("input", render);
  $("#filter").addEventListener("change", render);
  document.addEventListener("keydown", e => {
    if (e.key === "n" && !e.ctrlKey && !e.metaKey && !e.altKey && !/input|textarea|select/i.test(e.target.tagName) && !$("#app").hidden && !$("#edit").open) { e.preventDefault(); $("#new-title").focus(); }
  });

  /* ---------- edit dialog ---------- */
  const dlg = $("#edit"), form = $("#edit-form");

  function openEdit(id) {
    const t = tasks.find(x => x.id === id);
    editingId = id; deleteArmed = false;
    clearErrors(form); $("#edit-error").textContent = "";
    $("#delete").textContent = "Delete task";
    form.elements.title.value = t.title; form.notes.value = t.notes || ""; form.priority.value = t.priority; form.due.value = t.due || "";
    $("#history").replaceChildren(...[{ status: "todo", at: t.created }, ...t.history].map(h =>
      el("li", { text: STATUS[h.status] + ", " + new Date(h.at).toLocaleString() })));
    dlg.showModal();
  }

  form.addEventListener("submit", async e => {
    e.preventDefault();
    clearErrors(form);
    const body = { title: form.elements.title.value.trim(), notes: form.notes.value, priority: form.priority.value, due: form.due.value || null };
    if (!body.title) { showFieldErrors(form, { title: "Give the task a title." }); return; }
    const r = await api("PATCH", "/api/tasks/" + editingId, body);
    if (r.ok) { Object.assign(tasks.find(x => x.id === editingId), r.data); dlg.close(); render(); }
    else if (r.data.errors) showFieldErrors(form, r.data.errors);
    else $("#edit-error").textContent = r.data.error || "That didn't save.";
  });

  $("#cancel").addEventListener("click", () => dlg.close());
  $("#delete").addEventListener("click", async e => {
    if (!deleteArmed) { deleteArmed = true; e.currentTarget.textContent = "Click again to delete for good"; return; }
    const r = await api("DELETE", "/api/tasks/" + editingId);
    if (r.ok) { tasks = tasks.filter(x => x.id !== editingId); dlg.close(); render(); }
    else $("#edit-error").textContent = r.data.error || "Couldn't delete that task.";
  });
  dlg.addEventListener("close", () => { editingId = null; });

  /* ---------- start ---------- */
  (async function start() {
    const r = await api("GET", "/api/me");
    if (r.ok) showApp(r.data.username);
    else { setMode("login"); showAuth(); }
  })();
})();
