#!/usr/bin/env python3
"""
Матрица путей «Осмотра» на эмуляторе: путь × ориентация × размер экрана × тема × шрифт.

Перенос практик APCS (tools/flow-matrix сайта, обход walk.py приложения APCS): путь — то, что
делает человек, по шагам; после каждого шага — «что осталось» (ожидания пути) и общие флаги
раскладки на каждом экране, который путь увидел:

  ПАДЕНИЕ      — в журнале падений новое (logcat -b crash);
  ВНЕ          — окно в фокусе — не «Осмотр» (вылетели, системный диалог), касания дальше не идут;
  ЗА КРАЕМ     — текст или кнопка вне окна, и это не прокручиваемое (до него не добраться);
  ПОД ВЫРЕЗОМ  — текст или кнопка под вырезом камеры, строкой состояния или панелью навигации;
  МЕЛКО        — кнопка меньше 36 dp по стороне (палец не попадает);
  НАЛОЖЕНИЕ    — две кнопки одного слоя перекрываются больше чем на треть меньшей.

  python3 tools/matrix/paths.py [--пути p1,p2] [--ориентации 0,1] [--размеры телефон,малый,планшет]
                                [--темы light,dark] [--шрифты 1.0,1.3] [--md отчёт.md] [--json отчёт.json]

Без ключей — 16 сочетаний: все ориентации × размеры × темы при шрифте 1,0 и телефон × ориентации ×
темы при 1,3. Нужны: запущенный эмулятор, установленный «Осмотр», доступ к памяти выдан. Опись
«02-inv1» (8 предметов) и «Корпус A» (папки со снимками) — из набора стенда (tools/make_templates.py);
файлы для загрузки путь кладёт сам в /sdcard/Download/matrix и убирает за собой.
Выход с ошибкой, если хоть один путь не прошёл или есть общие флаги.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ADB = os.path.expanduser('~/Android/Sdk/platform-tools/adb')
PKG = 'kg.osmotr'
ROOT = '/sdcard/Осмотры'
SIZES = {'телефон': None, 'малый': ('720x1280', 280), 'планшет': ('1600x2560', 320)}


def adb(*a, check=False, timeout=60):
    r = subprocess.run([ADB, *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr or r.stdout)
    return r.stdout


def sh(*a, **k):
    return adb('shell', *a, **k)


# ---------- Экран ----------

class Node:
    __slots__ = ('text', 'desc', 'rid', 'cls', 'clickable', 'scrollable', 'enabled', 'b', 'parent', 'kids', 'long')

    def __init__(self, e, parent):
        self.text = e.get('text', '')
        self.desc = e.get('content-desc', '')
        self.rid = e.get('resource-id', '')
        self.cls = e.get('class', '')
        self.clickable = e.get('clickable') == 'true'
        self.long = e.get('long-clickable') == 'true'
        self.scrollable = e.get('scrollable') == 'true'
        self.enabled = e.get('enabled') == 'true'
        m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', e.get('bounds', '[0,0][0,0]'))
        self.b = tuple(int(x) for x in m.groups())
        self.parent = parent
        self.kids = []

    @property
    def label(self):
        return self.text or self.desc or self.rid

    def named(self):
        """Чем узел назвать в отчёте: своё имя, иначе — текст внутри, иначе — соседа или родителя."""
        if self.label:
            return self.label
        stack = list(self.kids)
        while stack:
            k = stack.pop(0)
            if k.label:
                return 'внутри: ' + k.label
            stack += k.kids
        for a in self.ancestors():
            for k in a.kids:
                if k.label:
                    return f'{self.cls.split(".")[-1]} рядом с «{k.label}»'
        return self.cls

    def ancestors(self):
        p = self.parent
        while p is not None:
            yield p
            p = p.parent

    def scroller(self):
        return next((a for a in self.ancestors() if a.scrollable), None)

    def center(self):
        x1, y1, x2, y2 = self.b
        return (x1 + x2) // 2, (y1 + y2) // 2


class Screen:
    def __init__(self, xml):
        self.nodes = []
        root = ET.fromstring(xml)

        def walk(e, parent):
            n = Node(e, parent)
            if parent:
                parent.kids.append(n)
            self.nodes.append(n)
            for c in e.findall('node'):
                walk(c, n)
        for e in root.findall('node'):
            walk(e, None)
        self.package = root.find('node').get('package', '') if root.find('node') is not None else ''

    def texts(self):
        return [n.label for n in self.nodes if n.label]

    def find(self, want, exact=False):
        for n in self.nodes:
            for v in (n.text, n.desc, n.rid):
                if v and (v == want if exact else want in v):
                    return n
        return None

    def field(self):
        return next((n for n in self.nodes if n.cls == 'android.widget.EditText'), None)

    def has(self, want, exact=False):
        return self.find(want, exact) is not None

    def signature(self):
        """Чем экран узнаётся в отчёте: первые заметные надписи."""
        t = [x for x in (n.text for n in self.nodes) if x and len(x) > 2][:3]
        return ' / '.join(t)[:80]


class Device:
    def __init__(self):
        self.density = int(re.search(r'(\d+)', sh('wm', 'density').splitlines()[-1]).group(1))
        self.crash_mark = None

    # Устройство
    def window(self):
        out = sh('dumpsys', 'window', 'displays')
        m = re.search(r'cur=(\d+)x(\d+)', out)
        return (int(m.group(1)), int(m.group(2))) if m else (0, 0)

    def insets(self):
        """Видимые системные области: вырез, строка состояния, панель навигации."""
        out = sh('dumpsys', 'window')
        rects = set()
        for m in re.finditer(r'InsetsSource id=\S+ type=(statusBars|navigationBars|displayCutout) frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visible=true', out):
            r = tuple(int(x) for x in m.groups()[1:])
            if r[2] > r[0] and r[3] > r[1]:
                rects.add((m.group(1), r))
        return sorted(rects)

    def focus(self):
        out = sh('dumpsys', 'window')
        m = re.search(r'mCurrentFocus=Window\{\S+ \S+ ([^}\s]+)', out)
        return m.group(1) if m else ''

    def mark_crashes(self):
        sh('logcat', '-b', 'crash', '-c')

    def crashes(self):
        return sh('logcat', '-b', 'crash', '-d').strip()

    # Действия
    def launch(self):
        sh('am', 'force-stop', PKG)
        sh('monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
        time.sleep(3)

    def dump(self, tries=4):
        last = ''
        for _ in range(tries):
            out = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=30)
            i = out.find('<?xml')
            j = out.rfind('</hierarchy>')
            if i >= 0 and j > i:
                return Screen(out[i:j + len('</hierarchy>')])
            last = out
            time.sleep(1)
        raise RuntimeError('дерево экрана не снялось: ' + last[:200])

    def tap_node(self, n, long=False):
        x, y = n.center()
        if long:
            sh('input', 'swipe', str(x), str(y), str(x), str(y), '800')
        else:
            sh('input', 'tap', str(x), str(y))

    def rotate(self, r):
        sh('settings', 'put', 'system', 'accelerometer_rotation', '0')
        sh('settings', 'put', 'system', 'user_rotation', str(r))

    def theme(self, dark):
        sh('cmd', 'uimode', 'night', 'yes' if dark else 'no')

    def font(self, scale):
        sh('settings', 'put', 'system', 'font_scale', str(scale))

    def size(self, s):
        if s is None:
            sh('wm', 'size', 'reset'); sh('wm', 'density', 'reset')
        else:
            sh('wm', 'size', s[0]); sh('wm', 'density', str(s[1]))
        time.sleep(1.5)
        self.density = int(re.search(r'(\d+)\s*$', sh('wm', 'density').strip()).group(1))

    def dp(self, px):
        return px * 160 / self.density


# ---------- Общие флаги раскладки ----------

def layout_flags(scr, dev, win, insets):
    flags = []
    W, H = win
    seen = set()
    marked = [n for n in scr.nodes if (n.label or n.clickable) and n.b[2] > n.b[0] and n.b[3] > n.b[1]]
    for n in marked:
        x1, y1, x2, y2 = n.b
        sc = n.scroller()
        if sc is None and (x1 < -2 or y1 < -2 or x2 > W + 2 or y2 > H + 2):
            flags.append(('ЗА КРАЕМ', f'«{n.label[:40]}» {n.b} окно {W}×{H}'))
        if n.label or n.clickable:
            for kind, (a1, b1, a2, b2) in insets:
                ix = min(x2, a2) - max(x1, a1); iy = min(y2, b2) - max(y1, b1)
                if ix > 4 and iy > 4 and (n.text or n.clickable):
                    # Прокручиваемое уходит под панель нарочно — флаг только у неподвижного.
                    if sc is None:
                        flags.append(('ПОД ВЫРЕЗОМ', f'«{n.label[:40]}» {n.b} под {kind} {(a1, b1, a2, b2)}'))
        if n.clickable and n.enabled:
            # Размер цели — вместе с вложенным: рамка кнопки бывает уже её же Button внутри
            # (верхняя панель Material отдаёт так «Назад»), а касание ловится по всей кнопке.
            xs, ys = [x1, x2], [y1, y2]
            stack = list(n.kids)
            while stack:
                k = stack.pop(); stack += k.kids
                if not k.clickable:
                    xs += [k.b[0], k.b[2]]; ys += [k.b[1], k.b[3]]
            w, h = dev.dp(max(xs) - min(xs)), dev.dp(max(ys) - min(ys))
            # Обрезанное прокруткой у края — не мелкое, а уехавшее.
            if sc is not None:
                s = sc.b
                if x1 <= s[0] or y1 <= s[1] or x2 >= s[2] or y2 >= s[3]:
                    continue
            if (w < 36 or h < 36) and n.label not in seen:
                seen.add(n.label)
                flags.append(('МЕЛКО', f'«{n.named()[:60]}» {w:.0f}×{h:.0f} dp {n.b}'))
    clicks = [n for n in marked if n.clickable and n.enabled]
    for i, a in enumerate(clicks):
        for b in clicks[i + 1:]:
            if a in b.ancestors() or b in a.ancestors() or a.scroller() is not b.scroller():
                continue
            ix = min(a.b[2], b.b[2]) - max(a.b[0], b.b[0]); iy = min(a.b[3], b.b[3]) - max(a.b[1], b.b[1])
            if ix <= 0 or iy <= 0:
                continue
            small = min((a.b[2] - a.b[0]) * (a.b[3] - a.b[1]), (b.b[2] - b.b[0]) * (b.b[3] - b.b[1]))
            if small and ix * iy > small / 3:
                flags.append(('НАЛОЖЕНИЕ', f'«{a.named()[:30]}» и «{b.named()[:30]}»'))
    return flags


# ---------- Путь ----------

class Fail(Exception):
    pass


class Run:
    """Один путь в одном сочетании: шаги, их итоги и флаги экранов."""

    def __init__(self, dev, name):
        self.dev, self.name = dev, name
        self.steps = []
        self.flags = []
        self.scr = None

    def look(self, what):
        time.sleep(1.2)
        f = self.dev.focus()
        if 'Pop-Up' in f:
            time.sleep(1)  # меню ещё раскрывается — снимок застал бы окно под ним
        # Меню «⋮» и подсказки — свои всплывающие окна «Осмотра» (Pop-Up), выбор файлов — системный.
        if PKG not in f and 'documentsui' not in f and 'Pop-Up' not in f:
            self.steps.append({'что': what, 'ок': False, 'было': f'в фокусе {f}', 'ждали': PKG})
            raise Fail()
        c = self.dev.crashes()
        if c:
            self.steps.append({'что': what, 'ок': False, 'было': 'ПАДЕНИЕ ' + c[-800:], 'ждали': 'без падений'})
            raise Fail()
        self.scr = self.dev.dump()
        if 'documentsui' not in f:
            win = self.dev.window()
            for flag, detail in layout_flags(self.scr, self.dev, win, self.dev.insets()):
                self.flags.append({'экран': self.scr.signature(), 'шаг': what, 'флаг': flag, 'подробно': detail})
        return self.scr

    def expect(self, what, has=(), lacks=(), exact=False):
        scr = self.scr
        miss = [h for h in has if not scr.has(h, exact)]
        extra = [h for h in lacks if scr.has(h, exact)]
        ok = not miss and not extra
        self.steps.append({'что': what, 'ок': ok, 'было': {'нет': miss, 'лишнее': extra, 'на экране': scr.texts()[:25]} if not ok else '',
                           'ждали': {'есть': list(has), 'нет': list(lacks)}})
        if not ok:
            raise Fail()

    def tap(self, want, what=None, long=False, exact=False, look=True):
        n = self.scr.find(want, exact)
        for _ in range(2):  # ещё появляется (меню раскрывается, список грузится)
            if n is not None:
                break
            time.sleep(1)
            self.scr = self.dev.dump()
            n = self.scr.find(want, exact)
        if n is None:
            self.steps.append({'что': what or f'нажать «{want}»', 'ок': False, 'было': self.scr.texts()[:30], 'ждали': want})
            raise Fail()
        self.dev.tap_node(n, long)
        if look:
            self.look(what or f'нажать «{want}»')

    def search(self, text):
        n = self.scr.field()
        if n is None:
            self.steps.append({'что': 'поле поиска', 'ок': False, 'было': self.scr.texts()[:30], 'ждали': 'EditText'})
            raise Fail()
        self.dev.tap_node(n); time.sleep(0.8)
        self.type(text, f'поиск: {text}')

    def found(self):
        n = next((n for n in self.scr.nodes if n.text.startswith('Найдено:')), None)
        return n.text if n else ''

    def type(self, text, what=None):
        sh('input', 'text', text)
        self.look(what or f'набрать {text}')

    def back(self, what='назад'):
        sh('input', 'keyevent', 'BACK')
        self.look(what)

    def scroll_side_to(self, want, tries=6):
        """Таблица вбок — к столбцу за правым краем."""
        for _ in range(tries):
            if self.scr.has(want, exact=True):
                return
            t = self.scr.find('таблица')
            x1, y1, x2, y2 = t.b if t else (0, 0, *self.dev.window())
            y = y1 + 200
            sh('input', 'swipe', str(int(x2 * 0.85)), str(y), str(int(x1 + (x2 - x1) * 0.3)), str(y), '400')
            self.look(f'вбок к «{want}»')
        self.expect(f'вбок к «{want}»', has=[want], exact=True)

    def scroll_to(self, want, tries=8):
        for _ in range(tries):
            if self.scr.has(want):
                return
            W, H = self.dev.window()
            sh('input', 'swipe', str(W // 2), str(int(H * 0.75)), str(W // 2), str(int(H * 0.35)), '300')
            self.look(f'прокрутить к «{want}»')
        self.expect(f'прокрутить к «{want}»', has=[want])


def open_inventory(r, name):
    r.dev.launch()
    r.look('главная')
    r.scroll_to(name)
    r.tap(name, f'открыть опись «{name}»')
    r.expect('опись открылась', has=['осмотрено'])


def fresh(r):
    """Своя опись пути — свежая, из набора (12 шкафов «МТ-101…112», лист «Сводка» рядом)."""
    r.dev.launch(); r.look('главная')
    view(r, 'matrix-good.xlsx', 'загрузить опись пути')
    r.expect('опись открылась', has=['из 12 осмотрено', 'МТ-101'])


# Пути. Каждый начинает с чистого запуска и своей свежей описи.

def p_search_card(r):
    fresh(r)
    r.search('105')
    r.expect('нашлось по номеру', has=['МТ-105'])
    r.tap('Шкаф металлический 5', 'открыть найденное')
    r.expect('карточка предмета', has=['МТ-105', 'Опись «matrix-good»'])
    r.back()
    r.expect('вернулись в опись, поиск чист', has=['из 12 осмотрено'], lacks=['Найдено:'])


def p_mark_selection(r):
    fresh(r)
    r.tap('Шкаф металлический 1', 'выделить долгим нажатием', long=True, exact=True)
    r.expect('панель выделения', has=['Отмечено: 1', 'Осмотрено (1)'])
    r.tap('Осмотрено (1)')
    r.expect('метка «осмотрено» у предмета', has=['осмотрено'], exact=True)
    r.tap('Шкаф металлический 1', 'выделить снова', long=True, exact=True)
    r.tap('Снять (1)')
    r.expect('метка снята', lacks=['осмотрено'], exact=True)


def p_mark_card(r):
    fresh(r)
    r.tap('МТ-102', 'открыть предмет', exact=True)
    r.tap('Отметить осмотренным')
    r.expect('отмечено в карточке', has=['Осмотрено — снять отметку'])
    r.tap('Осмотрено — снять отметку')
    r.expect('снято в карточке', has=['Отметить осмотренным'])
    r.back()
    r.expect('в описи', has=['из 12 осмотрено'])
    r.expect('без метки', lacks=['осмотрено'], exact=True)


def p_rotate_theme(r, ctx):
    fresh(r)
    r.search('10')
    before = r.found()
    r.expect('нашлось', has=['Найдено:'])
    r.dev.rotate(1 - ctx['rot']); time.sleep(1.5)
    r.look('повернули')
    r.expect('после поворота — тот же поиск', has=[before])
    r.dev.theme(not ctx['dark']); time.sleep(1.5)
    r.look('сменили тему')
    r.expect('после смены темы — тот же поиск', has=[before])
    r.dev.theme(ctx['dark']); r.dev.rotate(ctx['rot']); time.sleep(1.5)
    r.look('вернули тему и поворот')
    r.expect('после возврата — тот же поиск', has=[before])


def p_columns_cancel(r):
    fresh(r)
    r.tap('Ещё', 'меню', exact=True)
    r.tap('Столбцы описи')
    r.expect('экран столбцов', has=['Столбцы описи', 'Инв. №', 'Наименование', 'Сохранить', 'Как прочитано:', 'Сводка'])
    r.tap('Наименование', 'столбец наименований', exact=True)
    r.expect('выбор поля', has=['Инвентарный номер', 'Не брать этот столбец'])
    r.tap('Инвентарный номер', 'указать: инвентарник', exact=True)
    r.expect('подпись сменилась, предупреждение есть', has=['Инв. №', 'проверьте столбец'])
    r.back('назад с несохранённым')
    r.expect('спросили', has=['Не сохранять изменения?'])
    r.tap('Не сохранять', exact=True)
    r.expect('опись как была', has=['из 12 осмотрено', 'МТ-101'])


def p_columns_save(r):
    fresh(r)
    r.expect('стоимость есть', has=['первонач.'])
    r.tap('Ещё', 'меню', exact=True)
    r.tap('Столбцы описи')
    r.scroll_side_to('Стоимость')
    r.tap('Стоимость', 'столбец стоимости', exact=True)
    r.tap('Не брать этот столбец')
    r.tap('Сохранить')
    r.expect('стоимости нет', has=['из 12 осмотрено'], lacks=['первонач.'])
    r.tap('Ещё', 'меню', exact=True)
    r.tap('Столбцы описи')
    r.tap('Как решит приложение')
    r.tap('Сохранить')
    r.expect('стоимость вернулась', has=['первонач.'])


def p_sheet_skip(r):
    fresh(r)
    r.tap('Ещё', 'меню', exact=True)
    r.tap('Столбцы описи')
    r.tap('лист Опись', 'лист «Опись»')
    r.tap('брать лист', 'не брать лист')
    r.expect('предметов нет — сохранить нельзя', has=['Ни одного предмета'])
    r.tap('брать лист', 'брать снова')
    r.expect('снова 12', has=['12 предметов'])
    r.back('назад: изменений нет')
    r.expect('опись, без вопроса', has=['из 12 осмотрено'], lacks=['Не сохранять изменения?'])


FIXTURES = '/sdcard/Download/matrix'


def view(r, name, what):
    sh('am', 'start', '-a', 'android.intent.action.VIEW', '-d', f'file://{FIXTURES}/{name}',
       '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-n', f'{PKG}/.MainActivity')
    time.sleep(2.5)
    r.look(what)


def p_bad_files(r):
    r.dev.launch(); r.look('главная')
    view(r, 'matrix-broken.xlsx', 'битый файл')
    r.expect('причина — повреждён', has=['повреждён'])
    r.back('закрыть окно')
    view(r, 'matrix-pdf.xlsx', 'PDF под видом xlsx')
    r.expect('причина — PDF', has=['PDF'])
    r.back('закрыть окно')
    view(r, 'matrix-act.xlsx', 'книга без списка')
    r.expect('опись заведена, столбцы спрошены', has=['Столбцы описи', 'Список предметов сам не нашёлся'])
    r.back('закрыть столбцы')
    r.expect('пустое состояние описи', has=['Указать столбцы', 'Загрузить другой файл'])
    view(r, 'matrix-good.xlsx', 'хорошая опись')
    r.expect('открылась', has=['из 12 осмотрено'])
    view(r, 'matrix-good.xlsx', 'та же второй раз')
    r.expect('уже загружена', has=['уже загружена'])


def p_replace_wrong(r):
    # Опись стенда со снимками в папках: замене есть что терять (пустую папку приложение убирает само).
    open_inventory(r, 'Корпус A')
    r.tap('Ещё', 'меню', exact=True)
    r.tap('Заменить файл описи', 'выбор файла')
    # Системный выбор файлов: к папке Download/matrix.
    for _ in range(4):
        if r.scr.has('matrix-other.xlsx'):
            break
        for want in ('Show roots', 'Показать корневые папки', 'Downloads', 'Загрузки', 'matrix'):
            n = r.scr.find(want)
            if n is not None:
                r.dev.tap_node(n); r.look(f'выбор файла: {want}')
    r.tap('matrix-other.xlsx', 'выбрать чужую опись')
    time.sleep(3); r.look('проверка замены')
    r.expect('спросили перед заменой', has=['Заменить файл описи?', 'Похоже, это другая опись.'])
    r.tap('Отмена')
    r.expect('опись как была', has=['из 5709 осмотрено'])


def p_screens(r):
    r.dev.launch(); r.look('главная')
    r.scroll_to('Корпус A')
    r.tap('Корпус A', 'опись')
    r.tap('Папки описи', 'папки описи')
    r.look('папки')
    for want in ('Поиск', 'Дерево папок'):
        n = r.scr.find(want)
        if n is not None:
            r.dev.tap_node(n); r.look(want)
            r.back(f'назад из «{want}»')


PATHS = {
    'поиск → карточка → назад': p_search_card,
    'осмотрено выделением и снять': p_mark_selection,
    'осмотрено в карточке и снять': p_mark_card,
    'поворот и тема не теряют поиск': p_rotate_theme,
    'столбцы: поправить и не сохранять': p_columns_cancel,
    'столбцы: не брать стоимость и вернуть': p_columns_save,
    'столбцы: лист не брать и вернуть': p_sheet_skip,
    'битый, PDF, без списка, повтор': p_bad_files,
    'замена чужой описью — спросить': p_replace_wrong,
    'экраны: папки, поиск, дерево': p_screens,
}


def fixtures():
    """Файлы для загрузки — openpyxl, как tools/make_templates.py."""
    import openpyxl
    d = tempfile.mkdtemp()

    def book(name, sheets):
        wb = openpyxl.Workbook(); wb.remove(wb.active)
        for title, rows in sheets:
            ws = wb.create_sheet(title)
            for r in rows:
                ws.append(r)
        wb.save(os.path.join(d, name))
    book('matrix-good.xlsx', [('Сводка', [['Объект', 'Матрица'], ['Предметов', 12]]),
                              ('Опись', [['№ п/п', 'Наименование', 'Инв. номер', 'Стоимость']] +
                               [[i, f'Шкаф металлический {i}', f'МТ-{100 + i}', 1000 * i] for i in range(1, 13)])])
    book('matrix-other.xlsx', [('Опись', [['№', 'Наименование', 'Инв. номер']] + [[i, f'Кресло {i}', f'ДР-{i}'] for i in range(1, 6)])])
    book('matrix-act.xlsx', [('Акт', [['Акт приёма-передачи'], ['Комиссия в составе:'], ['Иванов И. И.'], ['Петров П. П.'], ['Подписи']])])
    good = open(os.path.join(d, 'matrix-good.xlsx'), 'rb').read()
    open(os.path.join(d, 'matrix-broken.xlsx'), 'wb').write(good[:len(good) // 2])
    open(os.path.join(d, 'matrix-pdf.xlsx'), 'wb').write(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n1 0 obj\n<<>>\nendobj\n')
    sh('rm', '-rf', FIXTURES); sh('mkdir', '-p', FIXTURES)
    for f in os.listdir(d):
        adb('push', os.path.join(d, f), f'{FIXTURES}/{f}', check=True)
    # Видно системному выбору файлов.
    for f in os.listdir(d):
        sh('am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', f'file://{FIXTURES}/{f}')


def cleanup():
    """Описи, заведённые путями, — прочь (и журнал «Осмотра» о них не помнит: имена новые каждый прогон)."""
    # adb shell склеивает аргументы в одну строку для оболочки устройства — команда целиком.
    sh(f"rm -rf '{ROOT}'/matrix-*")


def main():
    args = sys.argv[1:]

    def key(name, default):
        return args[args.index(name) + 1] if name in args else default
    rots = [int(x) for x in key('--ориентации', '0,1').split(',')]
    sizes = key('--размеры', 'телефон,малый,планшет').split(',')
    themes = key('--темы', 'light,dark').split(',')
    fonts = [float(x) for x in key('--шрифты', '1.0,1.3').split(',')]
    only = key('--пути', '')
    paths = {k: v for k, v in PATHS.items() if not only or any(o in k for o in only.split(','))}
    combos = []
    for f in fonts:
        for s in sizes:
            if f != 1.0 and s != 'телефон' and '--шрифты' not in args:
                continue
            for rot in rots:
                for t in themes:
                    combos.append({'rot': rot, 'size': s, 'theme': t, 'font': f, 'dark': t == 'dark'})
    dev = Device()
    fixtures()
    runs = []
    try:
        for ctx in combos:
            label = f"{'лёжа' if ctx['rot'] else 'стоя'}, {ctx['size']}, {ctx['theme']}, шрифт {ctx['font']}"
            sys.stderr.write(label + '\n')
            dev.size(SIZES[ctx['size']]); dev.rotate(ctx['rot']); dev.theme(ctx['dark']); dev.font(ctx['font'])
            for name, fn in paths.items():
                cleanup()
                dev.mark_crashes()
                r = Run(dev, name)
                ok = True
                try:
                    fn(r, ctx) if fn.__code__.co_argcount == 2 else fn(r)
                except Fail:
                    ok = False
                except Exception as e:  # noqa: BLE001 — сам прогон упал: это тоже ✗ с причиной
                    ok = False
                    r.steps.append({'что': 'прогон', 'ок': False, 'было': repr(e)[:400], 'ждали': 'без исключений'})
                runs.append({'сочетание': label, 'путь': name, 'ок': ok, 'шаги': r.steps, 'флаги': r.flags})
                sys.stderr.write(f"  {'✓' if ok else '✗'} {name}  флагов {len(r.flags)}\n")
                # Вернуть сочетание, если путь его сменил и упал на полдороге.
                dev.rotate(ctx['rot']); dev.theme(ctx['dark'])
    finally:
        cleanup()
        sh('rm', '-rf', FIXTURES)
        dev.size(None); dev.rotate(0); dev.font(1.0)
        sh('settings', 'put', 'system', 'accelerometer_rotation', '1')

    labels = list(dict.fromkeys(r['сочетание'] for r in runs))
    names = list(dict.fromkeys(r['путь'] for r in runs))
    out = ['# Матрица путей «Осмотра»', '',
           '✓ — путь прошёл, ✗ — нет; число — общие флаги раскладки на экранах пути (подробности ниже).', '',
           '| путь | ' + ' | '.join(labels) + ' |', '|---|' + '---|' * len(labels)]
    for n in names:
        cells = []
        for l in labels:
            r = next((x for x in runs if x['путь'] == n and x['сочетание'] == l), None)
            cells.append('' if r is None else ('✓' if r['ок'] else '✗') + (f" {len(r['флаги'])}" if r['флаги'] else ''))
        out.append(f'| {n} | ' + ' | '.join(cells) + ' |')
    bad = [r for r in runs if not r['ок']]
    if bad:
        out += ['', '## Не прошло', '']
        for r in bad:
            out.append(f"### {r['путь']} — {r['сочетание']}")
            for s in r['шаги']:
                if not s['ок']:
                    out.append(f"- {s['что']}: было `{json.dumps(s['было'], ensure_ascii=False)[:600]}`, ждали `{json.dumps(s['ждали'], ensure_ascii=False)}`")
            out.append('')
    flags = {}
    for r in runs:
        for f in r['флаги']:
            flags.setdefault((f['флаг'], f['подробно']), set()).add(f"{r['сочетание']} · {f['шаг']}")
    if flags:
        out += ['', '## Флаги раскладки', '', '| флаг | что | где (сочетание · шаг) |', '|---|---|---|']
        for (fl, det), where in sorted(flags.items()):
            w = sorted(where)
            out.append(f"| **{fl}** | {det} | {'; '.join(w[:3])}{f' и ещё {len(w) - 3}' if len(w) > 3 else ''} |")
    text = '\n'.join(out) + '\n'
    md = key('--md', '')
    if md:
        open(md, 'w').write(text)
    else:
        sys.stdout.write(text)
    js = key('--json', '')
    if js:
        json.dump(runs, open(js, 'w'), ensure_ascii=False, indent=1)
    sys.stderr.write(f'путей не прошло: {len(bad)}, флагов раскладки: {len(flags)}\n')
    sys.exit(1 if bad or flags else 0)


if __name__ == '__main__':
    main()
