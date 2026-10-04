const $ = id => document.getElementById(id);
const state = {employees: [], entries: [], employeeId: '', entryId: null, employeeEditId: null, totalUnavailable:false};
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const hours = value => Number(value || 0).toLocaleString(undefined, {maximumFractionDigits: 2});
const today = () => {const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
$('month').value = today().slice(0,7);
async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {...options, headers: {'Content-Type':'application/json', ...options.headers}});
  if (!response.ok) {let message = `Request failed (${response.status}).`; try {const body = await response.json(); message = body.message || body.error || message;} catch {} throw new Error(message);}
  return response.status === 204 ? null : response.json();
}
function notice(message, error = false) {if (!error && state.totalUnavailable) {message += ' Monthly total unavailable. Your time entries are still available.'; error = true;} $('notice').textContent = message; $('notice').classList.toggle('error',error); $('notice').hidden = false;}
async function act(work) {try {await work();} catch (error) {notice(error.message,true); const dialog = document.querySelector('dialog[open]'); if (dialog) {let message = dialog.querySelector('.dialog-error'); if (!message) {message = document.createElement('p'); message.className = 'notice error dialog-error'; message.setAttribute('role','alert'); dialog.querySelector('.dialog-heading').after(message);} message.textContent = error.message;}}}
async function health() {try {const result = await api('/health'); const healthy = ['healthy','ok','up'].includes(String(result.status).toLowerCase()); $('health').textContent = healthy ? 'Service healthy' : `Service ${result.status || 'attention needed'}`; $('health').classList.toggle('good',healthy);} catch {$('health').textContent = 'Service unavailable'; $('health').classList.remove('good');}}
async function loadEmployees() {
  state.employees = await api('/employees');
  if (!state.employees.some(e => String(e.id) === String(state.employeeId))) state.employeeId = state.employees[0]?.id ?? '';
  $('employee-select').innerHTML = state.employees.length ? state.employees.map(e => `<option value="${escapeHtml(e.id)}">${escapeHtml(e.name)}</option>`).join('') : '<option value="">Add a team member first</option>';
  $('employee-select').value = state.employeeId; $('new-entry').disabled = !state.employeeId; renderTeam(); await loadTimesheet();
}
async function loadTimesheet() {
  const person = state.employees.find(e => String(e.id) === String(state.employeeId));
  const month = $('month').value;
  if (!month) return;
  state.totalUnavailable = false;
  let sheet = {entries:[],totalHours:0};
  let totalUnavailable = false;
  if (person) {
    const query = `employeeId=${encodeURIComponent(state.employeeId)}&month=${encodeURIComponent(month)}`;
    try {sheet = await api(`/timesheets?${query}`);} catch (error) {
      totalUnavailable = true;
      state.totalUnavailable = true;
      // The ledger remains usable when the monthly summary cannot be calculated.
      state.entries = []; $('entries').innerHTML = ''; $('total-hours').textContent = '—';
      sheet = {entries:await api(`/entries?${query}`),totalHours:null};
      notice(`Monthly total unavailable. Your time entries are still available. ${error.message}`,true);
    }
  }
  state.entries = sheet.entries || [];
  $('total-hours').textContent = totalUnavailable ? '—' : hours(sheet.totalHours);
  $('total-hours').setAttribute('aria-label', totalUnavailable ? 'Monthly total unavailable' : `${hours(sheet.totalHours)} hours`);
  $('ledger-title').textContent = new Date(`${month}-01T12:00:00`).toLocaleDateString(undefined,{month:'long',year:'numeric'});
  $('entry-count').textContent = `${state.entries.length} ${state.entries.length === 1 ? 'entry' : 'entries'}`;
  $('summary-person').textContent = person ? `${person.name}${person.department ? ' · '+person.department : ''}` : 'Add a team member to begin.';
  $('empty').hidden = state.entries.length > 0;
  $('entries').innerHTML = [...state.entries].sort((a,b) => String(b.date).localeCompare(String(a.date))).map(entry => `<tr><td>${escapeHtml(new Date(`${entry.date}T12:00:00`).toLocaleDateString(undefined,{month:'short',day:'numeric'}))}</td><td>${escapeHtml(entry.description)}</td><td class="numeric">${hours(entry.hours)}</td><td><div class="row-actions"><button data-edit-entry="${escapeHtml(entry.id)}" aria-label="Edit time for ${escapeHtml(entry.date)}">Edit</button><button class="delete" data-delete-entry="${escapeHtml(entry.id)}" aria-label="Delete time for ${escapeHtml(entry.date)}">Delete</button></div></td></tr>`).join('');
  await health();
}
function renderTeam() {$('team-list').innerHTML = state.employees.length ? state.employees.map(e => `<div class="team-row"><div>${escapeHtml(e.name)}<small>${escapeHtml(e.department)}</small></div><div class="row-actions"><button data-edit-employee="${escapeHtml(e.id)}">Edit</button><button class="delete" data-delete-employee="${escapeHtml(e.id)}">Delete</button></div></div>`).join('') : '<p class="muted">Add your first team member below.</p>';}
function openEntry(entry = null) {
  $('entry-dialog').querySelector('.dialog-error')?.remove(); state.entryId = entry?.id ?? null; $('entry-title').textContent = entry ? 'Edit time' : 'Add time';
  $('entry-person').textContent = state.employees.find(e => String(e.id) === String(state.employeeId))?.name || '';
  $('entry-date').value = entry?.date || (today().startsWith($('month').value) ? today() : `${$('month').value}-01`);
  $('entry-hours').value = entry?.hours ?? ''; $('entry-description').value = entry?.description ?? ''; $('entry-dialog').showModal();
}
function resetEmployeeForm() {state.employeeEditId = null; $('employee-form').reset(); $('employee-form-title').textContent = 'Add a team member'; $('save-employee').textContent = 'Add team member'; $('cancel-employee-edit').hidden = true;}
$('employee-select').addEventListener('change', () => act(async () => {state.employeeId = $('employee-select').value; await loadTimesheet();}));
$('month').addEventListener('change', () => act(loadTimesheet));
$('new-entry').addEventListener('click', () => openEntry());
$('team-button').addEventListener('click', () => {$('team-dialog').querySelector('.dialog-error')?.remove(); resetEmployeeForm(); $('team-dialog').showModal();});
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('entry-form').addEventListener('submit', event => {event.preventDefault(); act(async () => {
  const button = event.submitter; button.disabled = true;
  try {const entry = {employeeId:state.employeeId,date:$('entry-date').value,hours:Number($('entry-hours').value),description:$('entry-description').value.trim(),submissionId:crypto.randomUUID()}; if (!entry.description) throw new Error('Enter a work description.'); await api(`/entries${state.entryId !== null ? '/'+encodeURIComponent(state.entryId) : ''}`,{method:state.entryId !== null ? 'PUT':'POST',body:JSON.stringify(entry)}); $('month').value = entry.date.slice(0,7); await loadTimesheet(); $('entry-dialog').close(); notice('Time saved.');} finally {button.disabled = false;}
});});
$('entries').addEventListener('click', event => act(async () => {
  const edit = event.target.closest('[data-edit-entry]'); const remove = event.target.closest('[data-delete-entry]');
  if (edit) openEntry(state.entries.find(e => String(e.id) === edit.dataset.editEntry));
  if (remove && confirm('Delete this time entry?')) {await api(`/entries/${encodeURIComponent(remove.dataset.deleteEntry)}`,{method:'DELETE'}); await loadTimesheet(); notice('Time entry deleted.');}
}));
$('employee-form').addEventListener('submit', event => {event.preventDefault(); act(async () => {
  const button = event.submitter; button.disabled = true;
  try {const data = {name:$('employee-name').value.trim(),department:$('employee-department').value.trim()}; if (!data.name || !data.department) throw new Error('Enter a name and department.'); const result = await api(`/employees${state.employeeEditId !== null ? '/'+encodeURIComponent(state.employeeEditId):''}`,{method:state.employeeEditId !== null ? 'PUT':'POST',body:JSON.stringify(data)}); state.employeeId = result.id; resetEmployeeForm(); await loadEmployees(); notice('Team member saved.');} finally {button.disabled = false;}
});});
$('cancel-employee-edit').addEventListener('click',resetEmployeeForm);
$('team-list').addEventListener('click', event => act(async () => {
  const edit = event.target.closest('[data-edit-employee]'); const remove = event.target.closest('[data-delete-employee]');
  if (edit) {const person = state.employees.find(e => String(e.id) === edit.dataset.editEmployee); state.employeeEditId = person.id; $('employee-name').value = person.name; $('employee-department').value = person.department; $('employee-form-title').textContent = 'Edit team member'; $('save-employee').textContent = 'Save changes'; $('cancel-employee-edit').hidden = false; $('employee-name').focus();}
  if (remove && confirm('Delete this team member and their time entries?')) {await api(`/employees/${encodeURIComponent(remove.dataset.deleteEmployee)}`,{method:'DELETE'}); resetEmployeeForm(); await loadEmployees(); notice('Team member deleted.');}
}));
document.querySelectorAll('[data-scenario]').forEach(button => button.addEventListener('click', () => act(async () => {
  button.disabled = true;
  try {const result = await api(`/scenarios/${button.dataset.scenario}/run`,{method:'POST'}); if (result.employeeId != null) state.employeeId = result.employeeId; if (result.month) $('month').value = result.month; $('scenario-result').textContent = JSON.stringify(result,null,2); $('scenario-result').hidden = false; await loadEmployees(); await health(); notice(result.message || 'Demo scenario completed.');} finally {button.disabled = false;}
})));
$('reset-demo').addEventListener('click', () => act(async () => {if (!confirm('Reset all demo team members and time entries?')) return; await api('/scenarios/reset',{method:'POST'}); $('scenario-result').hidden = true; await loadEmployees(); await health(); notice('Demo data reset.');}));
act(loadEmployees); health(); setInterval(health,15000);
