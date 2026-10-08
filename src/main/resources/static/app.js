const root = document.getElementById("content");
const breadcrumb = document.getElementById("breadcrumb");
const detailDialog = document.getElementById("detailDialog");
const editorDialog = document.getElementById("editorDialog");
const confirmDialog = document.getElementById("confirmDialog");
const state = {
    section: "home",
    page: 1,
    limit: 10,
    search: "",
    year: "",
    status: "",
    sortBy: "lastName",
    sortOrder: "asc",
    requestId: 0
};
const titles = {
    home: "Главная",
    students: "Студенты",
    teachers: "Преподаватели",
    courses: "Курсы"
};
const descriptions = {
    students: "Список студентов университета",
    teachers: "Список преподавателей университета",
    courses: "Список учебных курсов"
};
const statusLabels = {
    active: "Обучается",
    inactive: "Неактивен",
    graduated: "Выпустился",
    suspended: "Отстранён"
};
let toastTimer;

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[character]);
}

function field(value) {
    return value === null || value === undefined || value === "" ? "—" : escapeHtml(value);
}

function fullName(item) {
    return [item.firstName, item.lastName].filter(Boolean).join(" ") || "—";
}

function statusBadge(status) {
    return '<span class="badge ' + escapeHtml(status) + '">' +
        escapeHtml(statusLabels[status] || status || "Не указан") + "</span>";
}

function owned(student) {
    return student.owned === true;
}

function toast(text, error = false) {
    const element = document.getElementById("toast");
    element.textContent = text;
    element.className = "toast visible" + (error ? " error" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        element.className = "toast";
    }, 3800);
}

async function request(path, options = {}) {
    let response;
    try {
        response = await fetch(path, {
            headers: {"Accept": "application/json", ...(options.body ? {"Content-Type": "application/json"} : {})},
            ...options
        });
    } catch {
        throw new Error("Нет соединения с сервером. Проверьте подключение.");
    }
    if (response.status === 204) {
        return null;
    }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(payload.error?.message || "Не удалось выполнить запрос");
    }
    return payload;
}

function sectionHeader(title, subtitle, action = "") {
    return '<div class="section-head"><div>' +
        "<h1>" + escapeHtml(title) + "</h1><p class='subtitle'>" + escapeHtml(subtitle) +
        "</p></div>" + action + "</div>";
}

function loading() {
    return '<div class="loading-card"><span class="spinner"></span>Получаем данные…</div>';
}

function empty(title, message, retry = false) {
    return '<div class="empty"><div class="empty-symbol">▤</div><h3>' + escapeHtml(title) +
        "</h3><p>" + escapeHtml(message) + "</p>" +
        (retry ? '<button type="button" class="secondary" data-retry>Повторить</button>' : "") +
        "</div>";
}

function listEndpoint() {
    const params = new URLSearchParams({
        page: String(state.page),
        limit: String(state.limit),
        sortOrder: state.sortOrder
    });
    if (state.sortBy) {
        params.set("sortBy", state.sortBy);
    }
    if (state.search.trim()) {
        params.set("q", state.search.trim());
    } else if (state.year) {
        params.set("year", state.year);
    } else if (state.status) {
        params.set("status", state.status);
    }
    return "/api/portal/" + state.section + "?" + params;
}

function selectOptions(values, selected) {
    return values.map(entry => '<option value="' + escapeHtml(entry[0]) + '"' +
        (selected === entry[0] ? " selected" : "") + ">" + escapeHtml(entry[1]) + "</option>").join("");
}

function toolbar() {
    const sortFields = state.section === "students"
        ? [["lastName", "По фамилии"], ["firstName", "По имени"], ["year", "По курсу"], ["gpa", "По среднему баллу"]]
        : state.section === "teachers"
            ? [["lastName", "По фамилии"], ["firstName", "По имени"], ["department", "По кафедре"]]
            : [["name", "По названию"], ["code", "По коду"], ["credits", "По кредитам"]];

    let html = '<div class="toolbar"><form class="search" id="searchForm">' +
        '<input type="search" id="searchInput" aria-label="Поиск" placeholder="' +
        (state.section === "students" ? "Имя, фамилия или почта" : state.section === "courses" ? "Найти курс" : "Поиск в таблице") +
        '" value="' + escapeHtml(state.search) + '">' +
        '<button type="submit" class="secondary">Найти</button></form>';

    if (state.section === "students") {
        html += '<select id="yearFilter" aria-label="Курс обучения">' +
            selectOptions([["", "Все курсы"], ["1", "1 курс"], ["2", "2 курс"], ["3", "3 курс"], ["4", "4 курс"]], state.year) +
            '</select><select id="statusFilter" aria-label="Статус">' +
            selectOptions([["", "Все статусы"], ...Object.entries(statusLabels)], state.status) + "</select>";
    }

    html += '<select id="sortFilter" aria-label="Сортировка">' + selectOptions(sortFields, state.sortBy) +
        '</select><select id="directionFilter" aria-label="Направление сортировки">' +
        selectOptions([["asc", "По возрастанию"], ["desc", "По убыванию"]], state.sortOrder) +
        '</select><button type="button" class="text-button" data-reset>Сбросить</button></div>';
    return html;
}

function tableHead() {
    if (state.section === "students") {
        return ["НОМЕР", "СТУДЕНТ", "ПОЧТА", "КУРС", "СР. БАЛЛ", "СТАТУС", "ДЕЙСТВИЯ"];
    }
    if (state.section === "teachers") {
        return ["ТАБЕЛЬНЫЙ №", "ПРЕПОДАВАТЕЛЬ", "КАФЕДРА", "ДОЛЖНОСТЬ", "КАБИНЕТ", "ПОЧТА", "ДЕЙСТВИЯ"];
    }
    return ["КОД", "НАЗВАНИЕ КУРСА", "КРЕДИТЫ", "СЕМЕСТР", "ПРЕПОДАВАТЕЛЬ", "СТАТУС", "ДЕЙСТВИЯ"];
}

function rowMarkup(item) {
    const id = escapeHtml(item.id);
    const openButton = '<button type="button" class="mini-btn" data-open="' + id + '">Просмотр</button>';
    if (state.section === "students") {
        return "<tr><td>" + field(item.studentNumber) + '</td><td class="bold-cell">' +
            escapeHtml(fullName(item)) + "</td><td>" + field(item.email) +
            "</td><td>" + field(item.year) + "</td><td>" + field(item.gpa) + "</td><td>" +
            statusBadge(item.status) + '</td><td class="actions">' + openButton +
            (owned(item) ? '<button class="mini-btn" type="button" data-edit="' + id +
                '">Изменить</button><button class="mini-btn remove" type="button" data-delete="' +
                id + '">Удалить</button>' : "") + "</td></tr>";
    }
    if (state.section === "teachers") {
        return "<tr><td>" + field(item.employeeNumber) + '</td><td class="bold-cell">' +
            escapeHtml(fullName(item)) + "</td><td>" + field(item.department) +
            "</td><td>" + field(item.position) + "</td><td>" + field(item.office) +
            "</td><td>" + field(item.email) + '</td><td class="actions">' + openButton + "</td></tr>";
    }
    return "<tr><td>" + field(item.code) + '</td><td class="bold-cell">' +
        field(item.name || item.title) + "</td><td>" + field(item.credits) +
        "</td><td>" + field(item.semester) + "</td><td>" + field(item.teacherName) +
        "</td><td>" + statusBadge(item.status) + '</td><td class="actions">' + openButton + "</td></tr>";
}

function renderList(payload) {
    const items = payload.items || [];
    const pagination = payload.pagination || {};
    const title = titles[state.section];
    const button = state.section === "students"
        ? '<button type="button" class="primary" data-create>+ Добавить студента</button>' : "";
    const head = sectionHeader(title, descriptions[state.section], button);
    const page = pagination.page || state.page;
    const totalPages = pagination.totalPages ?? 0;
    const rows = state.section === "teachers" && state.search.trim()
        ? items.filter(item => JSON.stringify(item).toLowerCase().includes(state.search.trim().toLowerCase()))
        : items;
    const table = rows.length
        ? '<div class="table-wrap"><table><thead><tr>' +
            tableHead().map(name => "<th>" + name + "</th>").join("") +
            "</tr></thead><tbody>" + rows.map(rowMarkup).join("") + "</tbody></table></div>"
        : empty("Данные не найдены", "Попробуйте другой запрос или сбросьте фильтры.");
    const paginationHtml = '<div class="pagination"><span>Всего записей: ' +
        escapeHtml(pagination.total ?? items.length) +
        '</span><div class="pagination-actions"><button type="button" class="page-btn" data-page="' +
        (page - 1) + '"' + (page <= 1 ? " disabled" : "") +
        '>← Назад</button><strong>' + page + " / " + Math.max(1, totalPages) +
        '</strong><button type="button" class="page-btn" data-page="' + (page + 1) + '"' +
        (page >= totalPages ? " disabled" : "") + '>Далее →</button></div></div>';
    root.innerHTML = head + '<div class="panel">' + toolbar() + table + paginationHtml + "</div>";
}

async function loadHome() {
    root.innerHTML = sectionHeader(
        "Главная",
        "Основная информация"
    ) + loading();
    const requestId = ++state.requestId;
    const types = ["students", "teachers", "courses"];
    const results = await Promise.allSettled(types.map(type =>
        request("/api/portal/" + type + "?page=1&limit=1")
    ));
    if (requestId !== state.requestId) {
        return;
    }
    root.innerHTML = sectionHeader(
        "Главная",
        "Основная информация"
    ) + '<div class="stats">' + results.map((result, i) => {
        const count = result.status === "fulfilled" ? result.value.pagination?.total : null;
        return '<div class="stat-card"><div class="stat-label">' + titles[types[i]] +
            '</div><div class="stat-value">' + (count ?? "—") +
            '</div><div class="stat-foot">' + (count === null ? "Нет данных от сервиса" : "Всего в базе") +
            "</div></div>";
    }).join("") + '</div><h2 class="quick-title">Разделы</h2><div class="quick-grid">' +
        types.map(type => '<button type="button" class="quick-card" data-nav="' +
            type + '"><span>' + titles[type] + '</span><span>→</span></button>').join("") +
        '</div>';
}

async function loadList() {
    const requestId = ++state.requestId;
    root.innerHTML = sectionHeader(
        titles[state.section],
        descriptions[state.section],
        state.section === "students" ? '<button class="primary" type="button" data-create>+ Добавить студента</button>' : ""
    ) + loading();
    try {
        const payload = await request(listEndpoint());
        if (requestId === state.requestId) {
            renderList(payload);
        }
    } catch (error) {
        if (requestId === state.requestId) {
            root.innerHTML = sectionHeader(titles[state.section], descriptions[state.section]) +
                '<div class="panel">' + empty("Не удалось загрузить данные", error.message, true) + "</div>";
        }
    }
}

function navigate(section) {
    if (!titles[section]) {
        return;
    }
    state.section = section;
    state.page = 1;
    state.search = "";
    state.year = "";
    state.status = "";
    state.sortBy = section === "courses" ? "name" : "lastName";
    state.sortOrder = "asc";
    breadcrumb.textContent = titles[section];
    document.querySelectorAll(".nav-link").forEach(link =>
        link.classList.toggle("active", link.dataset.nav === section)
    );
    document.title = titles[section] + " — University Portal";
    history.replaceState({}, "", section === "home" ? "/" : "#" + section);
    refresh();
}

function refresh() {
    if (state.section === "home") {
        loadHome();
    } else {
        loadList();
    }
}

function info(label, value) {
    return '<div><div class="info-label">' + escapeHtml(label) +
        '</div><div class="info-value">' + field(value) + "</div></div>";
}

function dialogHeader(title, dialogId) {
    return '<div class="dialog-head"><h2>' + escapeHtml(title) +
        '</h2><button type="button" class="icon-button" data-close="' +
        dialogId + '" aria-label="Закрыть">×</button></div>';
}

async function showDetail(id) {
    detailDialog.showModal();
    document.getElementById("detailContent").innerHTML = dialogHeader("Загрузка…", "detailDialog") +
        '<div class="dialog-body">' + loading() + "</div>";
    try {
        const result = await request("/api/portal/" + state.section + "/" + encodeURIComponent(id));
        const item = result.item;
        let fields;
        let title;
        if (state.section === "students") {
            title = fullName(item);
            fields = [
                ["Номер студента", item.studentNumber],
                ["Имя и фамилия", fullName(item)],
                ["Электронная почта", item.email],
                ["Телефон", item.phone],
                ["Дата рождения", item.dateOfBirth],
                ["Курс обучения", item.year],
                ["Средний балл", item.gpa],
                ["Статус", statusLabels[item.status] || item.status]
            ];
        } else if (state.section === "teachers") {
            title = fullName(item);
            fields = [
                ["Табельный номер", item.employeeNumber],
                ["Преподаватель", fullName(item)],
                ["Кафедра", item.department],
                ["Должность", item.position],
                ["Кабинет", item.office],
                ["Телефон", item.phone],
                ["Электронная почта", item.email]
            ];
        } else {
            title = item.name || item.title || "Курс";
            fields = [
                ["Код курса", item.code],
                ["Название", item.name || item.title],
                ["Описание", item.description],
                ["Кредиты", item.credits],
                ["Семестр", item.semester],
                ["Статус", item.status],
                ["Зачислено студентов", item.enrolledStudents ?? item.enrolledCount],
                ["Вместимость", item.capacity],
                ["Преподаватель", item.teacherName]
            ];
        }
        document.getElementById("detailContent").innerHTML =
            dialogHeader(title, "detailDialog") + '<div class="dialog-body"><div class="info-grid">' +
            fields.map(pair => info(pair[0], pair[1])).join("") + '</div></div>' +
            (state.section === "students" && owned(item)
                ? '<div class="dialog-actions"><button type="button" class="danger" data-delete="' +
                    escapeHtml(item.id) + '">Удалить</button><button type="button" class="primary" data-edit="' +
                    escapeHtml(item.id) + '">Редактировать</button></div>'
                : "");
    } catch (error) {
        document.getElementById("detailContent").innerHTML = dialogHeader(
            "Запись не найдена", "detailDialog"
        ) + '<div class="dialog-body">' + empty(
            "Не удалось открыть запись", error.message, false
        ) + "</div>";
    }
}

function studentInput(label, name, value, type = "text", extra = "") {
    return '<label class="field">' + escapeHtml(label) +
        '<input name="' + name + '" type="' + type + '" value="' + escapeHtml(value ?? "") +
        '" ' + extra + '></label>';
}

function showEditor(item = null) {
    detailDialog.close();
    const isEdit = Boolean(item);
    const value = item || {};
    const fields =
        studentInput("Имя *", "firstName", value.firstName, "text", 'required maxlength="100"') +
        studentInput("Фамилия *", "lastName", value.lastName, "text", 'required maxlength="100"') +
        studentInput("Электронная почта *", "email", value.email, "email", "required") +
        studentInput("Телефон", "phone", value.phone, "tel") +
        studentInput("Дата рождения", "dateOfBirth", value.dateOfBirth, "date") +
        '<label class="field">Курс обучения *<select name="year" required>' +
        selectOptions([["1", "1 курс"], ["2", "2 курс"], ["3", "3 курс"], ["4", "4 курс"]],
            String(value.year || 1)) + "</select></label>" +
        studentInput("Средний балл *", "gpa", value.gpa ?? 0, "number", 'required min="0" max="4" step="0.01"') +
        '<label class="field">Статус *<select name="status" required>' +
        selectOptions(Object.entries(statusLabels), value.status || "active") + "</select></label>" +
        '<div class="field full">Номер студента: ' +
        (isEdit ? field(value.studentNumber) : "TEAM-01-… (присваивается автоматически)") + "</div>";
    document.getElementById("editorContent").innerHTML =
        dialogHeader(isEdit ? "Редактирование студента" : "Новый студент", "editorDialog") +
        '<form id="studentForm" data-id="' + escapeHtml(item?.id || "") + '">' +
        '<div class="dialog-body"><div class="field-grid">' + fields + '</div></div>' +
        '<div class="dialog-actions"><button type="button" class="secondary" data-close="editorDialog">Отмена</button>' +
        '<button type="submit" class="primary" id="saveButton">Сохранить</button></div></form>';
    editorDialog.showModal();
}

async function editStudent(id) {
    try {
        const result = await request("/api/portal/students/" + encodeURIComponent(id));
        if (!owned(result.item)) {
            toast("Эта запись принадлежит другой команде", true);
            return;
        }
        showEditor(result.item);
    } catch (error) {
        toast(error.message, true);
    }
}

async function saveStudent(form) {
    const data = Object.fromEntries(new FormData(form));
    data.year = Number(data.year);
    data.gpa = Number(data.gpa);
    if (!data.phone) {
        delete data.phone;
    }
    if (!data.dateOfBirth) {
        delete data.dateOfBirth;
    }
    if (!data.firstName.trim() || !data.lastName.trim() || data.year < 1 ||
        data.year > 4 || data.gpa < 0 || data.gpa > 4) {
        toast("Проверьте обязательные поля", true);
        return;
    }
    const button = document.getElementById("saveButton");
    button.disabled = true;
    button.textContent = "Сохраняем…";
    const id = form.dataset.id;
    try {
        await request(
            id ? "/api/portal/students/" + encodeURIComponent(id) : "/api/portal/students",
            {method: id ? "PATCH" : "POST", body: JSON.stringify(data)}
        );
        editorDialog.close();
        toast(id ? "Данные студента обновлены" : "Студент добавлен");
        navigate("students");
    } catch (error) {
        toast(error.message, true);
    } finally {
        button.disabled = false;
        button.textContent = "Сохранить";
    }
}

function confirmDelete(id) {
    detailDialog.close();
    document.getElementById("confirmContent").innerHTML =
        dialogHeader("Удалить студента?", "confirmDialog") +
        '<div class="dialog-body">Запись будет удалена без возможности восстановления. Продолжить?</div>' +
        '<div class="dialog-actions"><button class="secondary" type="button" data-close="confirmDialog">Отмена</button>' +
        '<button class="danger" type="button" data-confirm-delete="' + escapeHtml(id) + '">Удалить</button></div>';
    confirmDialog.showModal();
}

async function deleteStudent(id, button) {
    button.disabled = true;
    button.textContent = "Удаляем…";
    try {
        await request("/api/portal/students/" + encodeURIComponent(id), {method: "DELETE"});
        confirmDialog.close();
        toast("Студент удалён");
        refresh();
    } catch (error) {
        toast(error.message, true);
        button.disabled = false;
        button.textContent = "Удалить";
    }
}

document.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button) {
        return;
    }
    if (button.dataset.nav) {
        navigate(button.dataset.nav);
    } else if (button.hasAttribute("data-retry")) {
        refresh();
    } else if (button.hasAttribute("data-create")) {
        showEditor();
    } else if (button.dataset.open) {
        showDetail(button.dataset.open);
    } else if (button.dataset.edit) {
        editStudent(button.dataset.edit);
    } else if (button.dataset.delete) {
        confirmDelete(button.dataset.delete);
    } else if (button.dataset.confirmDelete) {
        deleteStudent(button.dataset.confirmDelete, button);
    } else if (button.dataset.close) {
        document.getElementById(button.dataset.close).close();
    } else if (button.dataset.page) {
        const page = Number(button.dataset.page);
        if (page >= 1 && !button.disabled) {
            state.page = page;
            loadList();
        }
    } else if (button.hasAttribute("data-reset")) {
        state.page = 1;
        state.search = "";
        state.year = "";
        state.status = "";
        state.sortBy = state.section === "courses" ? "name" : "lastName";
        state.sortOrder = "asc";
        loadList();
    }
});

document.addEventListener("submit", event => {
    if (event.target.id === "searchForm") {
        event.preventDefault();
        state.search = document.getElementById("searchInput").value.trim();
        state.year = "";
        state.status = "";
        state.page = 1;
        loadList();
    } else if (event.target.id === "studentForm") {
        event.preventDefault();
        saveStudent(event.target);
    }
});

document.addEventListener("change", event => {
    if (event.target.id === "yearFilter") {
        state.year = event.target.value;
        state.status = "";
        state.search = "";
    } else if (event.target.id === "statusFilter") {
        state.status = event.target.value;
        state.year = "";
        state.search = "";
    } else if (event.target.id === "sortFilter") {
        state.sortBy = event.target.value;
    } else if (event.target.id === "directionFilter") {
        state.sortOrder = event.target.value;
    } else {
        return;
    }
    state.page = 1;
    loadList();
});

const initial = location.hash.substring(1);
navigate(titles[initial] ? initial : "home");
