"""
멀티 포인트 오토 클리커

- 여러 클릭 위치(포인트)를 등록하고 순서대로 클릭합니다.
- 포인트마다 클릭 종류(왼쪽/오른쪽/더블)와 "클릭 후 대기 시간"을 지정할 수 있습니다.
- 전체 시퀀스 반복 횟수(0 = 무한)와 반복 사이 대기 시간을 지정할 수 있습니다.
- 전역 단축키: F6 = 현재 마우스 위치를 포인트로 추가, F9 = 시작/정지
- 설정을 JSON 파일로 저장/불러오기 할 수 있습니다.
"""

import json
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from pynput import keyboard, mouse


def enable_dpi_awareness():
    """Windows 화면 배율(125%, 150% 등)에서 좌표가 어긋나지 않도록 설정."""
    if sys.platform != "win32":
        return
    try:
        import ctypes

        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


CLICK_TYPES = {
    "왼쪽": (mouse.Button.left, 1),
    "오른쪽": (mouse.Button.right, 1),
    "가운데": (mouse.Button.middle, 1),
    "더블": (mouse.Button.left, 2),
}

HOTKEY_CAPTURE = keyboard.Key.f6
HOTKEY_TOGGLE = keyboard.Key.f9


class ClickerWorker(threading.Thread):
    """등록된 포인트를 순서대로 클릭하는 백그라운드 스레드."""

    def __init__(self, points, repeat, loop_delay_ms, start_delay_ms, events):
        super().__init__(daemon=True)
        self.points = points
        self.repeat = repeat
        self.loop_delay = loop_delay_ms / 1000
        self.start_delay = start_delay_ms / 1000
        self.events = events
        self.stop_event = threading.Event()
        self.mouse = mouse.Controller()

    def stop(self):
        self.stop_event.set()

    def run(self):
        try:
            if self.start_delay > 0:
                self.events.put(("status", f"{self.start_delay:g}초 후 시작..."))
                if self.stop_event.wait(self.start_delay):
                    return

            cycle = 0
            while not self.stop_event.is_set():
                cycle += 1
                total = "∞" if self.repeat == 0 else str(self.repeat)
                for idx, p in enumerate(self.points):
                    if self.stop_event.is_set():
                        return
                    self.events.put(("status", f"반복 {cycle}/{total} · 포인트 {idx + 1}/{len(self.points)}"))
                    self.events.put(("highlight", idx))
                    button, count = CLICK_TYPES[p["click"]]
                    self.mouse.position = (p["x"], p["y"])
                    time.sleep(0.01)  # 이동 후 클릭이 인식되도록 잠깐 대기
                    self.mouse.click(button, count)
                    if self.stop_event.wait(p["delay"] / 1000):
                        return

                if self.repeat and cycle >= self.repeat:
                    break
                if self.loop_delay > 0 and self.stop_event.wait(self.loop_delay):
                    return
        finally:
            self.events.put(("finished", None))


class AutoClickerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("멀티 포인트 오토 클리커")
        self.root.minsize(560, 520)

        self.points = []
        self.worker = None
        self.events = queue.Queue()
        self.mouse_ctl = mouse.Controller()

        self._build_ui()
        self._start_hotkeys()
        self._poll_events()
        self._update_mouse_pos()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    # ------------------------------------------------------------------ UI
    def _build_ui(self):
        pad = {"padx": 6, "pady": 4}

        # 현재 마우스 위치 / 안내
        top = ttk.Frame(self.root)
        top.pack(fill="x", **pad)
        self.pos_var = tk.StringVar(value="마우스 위치: -")
        ttk.Label(top, textvariable=self.pos_var, font=("", 10, "bold")).pack(side="left")
        ttk.Label(top, text="F6: 현재 위치 추가   F9: 시작/정지", foreground="#555").pack(side="right")

        # 포인트 목록
        list_frame = ttk.LabelFrame(self.root, text="클릭 포인트 (위에서부터 순서대로 실행)")
        list_frame.pack(fill="both", expand=True, **pad)

        cols = ("no", "x", "y", "click", "delay")
        self.tree = ttk.Treeview(list_frame, columns=cols, show="headings", height=10, selectmode="browse")
        for col, text, width in (
            ("no", "#", 40),
            ("x", "X", 80),
            ("y", "Y", 80),
            ("click", "클릭", 80),
            ("delay", "클릭 후 대기(ms)", 140),
        ):
            self.tree.heading(col, text=text)
            self.tree.column(col, width=width, anchor="center")
        scroll = ttk.Scrollbar(list_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="left", fill="y")
        self.tree.bind("<<TreeviewSelect>>", self.on_select)

        side = ttk.Frame(list_frame)
        side.pack(side="left", fill="y", padx=4)
        for text, cmd in (
            ("▲ 위로", lambda: self.move_point(-1)),
            ("▼ 아래로", lambda: self.move_point(1)),
            ("삭제", self.delete_point),
            ("전체 삭제", self.clear_points),
        ):
            ttk.Button(side, text=text, command=cmd, width=10).pack(fill="x", pady=2)

        # 포인트 편집
        edit = ttk.LabelFrame(self.root, text="포인트 편집")
        edit.pack(fill="x", **pad)

        self.x_var = tk.StringVar(value="0")
        self.y_var = tk.StringVar(value="0")
        self.click_var = tk.StringVar(value="왼쪽")
        self.delay_var = tk.StringVar(value="1000")

        ttk.Label(edit, text="X").grid(row=0, column=0, **pad)
        ttk.Entry(edit, textvariable=self.x_var, width=8).grid(row=0, column=1, **pad)
        ttk.Label(edit, text="Y").grid(row=0, column=2, **pad)
        ttk.Entry(edit, textvariable=self.y_var, width=8).grid(row=0, column=3, **pad)
        ttk.Label(edit, text="클릭").grid(row=0, column=4, **pad)
        ttk.Combobox(
            edit, textvariable=self.click_var, values=list(CLICK_TYPES), state="readonly", width=7
        ).grid(row=0, column=5, **pad)
        ttk.Label(edit, text="클릭 후 대기(ms)").grid(row=0, column=6, **pad)
        ttk.Entry(edit, textvariable=self.delay_var, width=8).grid(row=0, column=7, **pad)

        btns = ttk.Frame(edit)
        btns.grid(row=1, column=0, columnspan=8, sticky="w", **pad)
        ttk.Button(btns, text="추가", command=self.add_point).pack(side="left", padx=2)
        ttk.Button(btns, text="선택 항목 수정", command=self.update_point).pack(side="left", padx=2)
        ttk.Button(btns, text="3초 후 위치 가져오기", command=self.capture_delayed).pack(side="left", padx=2)

        # 실행 설정
        run = ttk.LabelFrame(self.root, text="실행 설정")
        run.pack(fill="x", **pad)

        self.repeat_var = tk.StringVar(value="0")
        self.loop_delay_var = tk.StringVar(value="0")
        self.start_delay_var = tk.StringVar(value="1000")

        ttk.Label(run, text="반복 횟수 (0=무한)").grid(row=0, column=0, **pad)
        ttk.Entry(run, textvariable=self.repeat_var, width=8).grid(row=0, column=1, **pad)
        ttk.Label(run, text="반복 사이 대기(ms)").grid(row=0, column=2, **pad)
        ttk.Entry(run, textvariable=self.loop_delay_var, width=8).grid(row=0, column=3, **pad)
        ttk.Label(run, text="시작 지연(ms)").grid(row=0, column=4, **pad)
        ttk.Entry(run, textvariable=self.start_delay_var, width=8).grid(row=0, column=5, **pad)

        # 하단 버튼
        bottom = ttk.Frame(self.root)
        bottom.pack(fill="x", **pad)
        self.start_btn = ttk.Button(bottom, text="▶ 시작 (F9)", command=self.toggle)
        self.start_btn.pack(side="left", padx=2)
        ttk.Button(bottom, text="저장", command=self.save).pack(side="left", padx=2)
        ttk.Button(bottom, text="불러오기", command=self.load).pack(side="left", padx=2)
        self.topmost_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(
            bottom, text="항상 위", variable=self.topmost_var, command=self._apply_topmost
        ).pack(side="right")
        self._apply_topmost()

        self.status_var = tk.StringVar(value="대기 중")
        ttk.Label(self.root, textvariable=self.status_var, relief="sunken", anchor="w").pack(
            fill="x", side="bottom"
        )

    def _apply_topmost(self):
        self.root.attributes("-topmost", self.topmost_var.get())

    # ------------------------------------------------------------ helpers
    @staticmethod
    def _parse_int(var, name, minimum=0):
        try:
            value = int(var.get().strip())
        except ValueError:
            raise ValueError(f"'{name}' 값은 정수여야 합니다.")
        if value < minimum:
            raise ValueError(f"'{name}' 값은 {minimum} 이상이어야 합니다.")
        return value

    def _read_editor(self):
        return {
            "x": self._parse_int(self.x_var, "X", minimum=-100000),
            "y": self._parse_int(self.y_var, "Y", minimum=-100000),
            "click": self.click_var.get(),
            "delay": self._parse_int(self.delay_var, "클릭 후 대기"),
        }

    def _refresh_tree(self, select=None):
        self.tree.delete(*self.tree.get_children())
        for i, p in enumerate(self.points):
            self.tree.insert("", "end", iid=str(i), values=(i + 1, p["x"], p["y"], p["click"], p["delay"]))
        if select is not None and 0 <= select < len(self.points):
            self.tree.selection_set(str(select))
            self.tree.see(str(select))

    def _selected_index(self):
        sel = self.tree.selection()
        return int(sel[0]) if sel else None

    def _is_running(self):
        return self.worker is not None and self.worker.is_alive()

    # ------------------------------------------------------- point actions
    def add_point(self, point=None):
        if self._is_running():
            return
        try:
            point = point or self._read_editor()
        except ValueError as e:
            messagebox.showerror("입력 오류", str(e))
            return
        self.points.append(point)
        self._refresh_tree(select=len(self.points) - 1)

    def update_point(self):
        idx = self._selected_index()
        if idx is None or self._is_running():
            return
        try:
            self.points[idx] = self._read_editor()
        except ValueError as e:
            messagebox.showerror("입력 오류", str(e))
            return
        self._refresh_tree(select=idx)

    def delete_point(self):
        idx = self._selected_index()
        if idx is None or self._is_running():
            return
        del self.points[idx]
        self._refresh_tree(select=min(idx, len(self.points) - 1))

    def clear_points(self):
        if self._is_running() or not self.points:
            return
        if messagebox.askyesno("전체 삭제", "모든 포인트를 삭제할까요?"):
            self.points.clear()
            self._refresh_tree()

    def move_point(self, offset):
        idx = self._selected_index()
        if idx is None or self._is_running():
            return
        new = idx + offset
        if 0 <= new < len(self.points):
            self.points[idx], self.points[new] = self.points[new], self.points[idx]
            self._refresh_tree(select=new)

    def on_select(self, _event=None):
        idx = self._selected_index()
        if idx is None or self._is_running():
            return
        p = self.points[idx]
        self.x_var.set(str(p["x"]))
        self.y_var.set(str(p["y"]))
        self.click_var.set(p["click"])
        self.delay_var.set(str(p["delay"]))

    def capture_current(self):
        x, y = (int(v) for v in self.mouse_ctl.position)
        self.x_var.set(str(x))
        self.y_var.set(str(y))
        try:
            delay = self._parse_int(self.delay_var, "클릭 후 대기")
        except ValueError:
            delay = 1000
        self.add_point({"x": x, "y": y, "click": self.click_var.get(), "delay": delay})
        self.status_var.set(f"포인트 추가: ({x}, {y})")

    def capture_delayed(self, remaining=3):
        if remaining > 0:
            self.status_var.set(f"{remaining}초 후 현재 마우스 위치를 추가합니다...")
            self.root.after(1000, self.capture_delayed, remaining - 1)
        else:
            self.capture_current()

    # ------------------------------------------------------------- running
    def toggle(self):
        if self._is_running():
            self.worker.stop()
            self.status_var.set("정지 중...")
        else:
            self.start()

    def start(self):
        if not self.points:
            messagebox.showwarning("포인트 없음", "먼저 클릭할 포인트를 추가하세요.")
            return
        try:
            repeat = self._parse_int(self.repeat_var, "반복 횟수")
            loop_delay = self._parse_int(self.loop_delay_var, "반복 사이 대기")
            start_delay = self._parse_int(self.start_delay_var, "시작 지연")
        except ValueError as e:
            messagebox.showerror("입력 오류", str(e))
            return
        if repeat == 0 and loop_delay == 0 and all(p["delay"] == 0 for p in self.points):
            messagebox.showerror("입력 오류", "무한 반복 시 대기 시간이 모두 0이면 정지하기 어렵습니다.\n대기 시간을 지정하세요.")
            return

        points = [dict(p) for p in self.points]
        self.worker = ClickerWorker(points, repeat, loop_delay, start_delay, self.events)
        self.worker.start()
        self.start_btn.config(text="■ 정지 (F9)")

    def _on_finished(self):
        self.start_btn.config(text="▶ 시작 (F9)")
        self.status_var.set("대기 중")
        self.tree.selection_remove(self.tree.selection())

    # ------------------------------------------------------- event plumbing
    def _poll_events(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "status":
                    self.status_var.set(value)
                elif kind == "highlight":
                    self.tree.selection_set(str(value))
                    self.tree.see(str(value))
                elif kind == "finished":
                    self._on_finished()
                elif kind == "hotkey_toggle":
                    self.toggle()
                elif kind == "hotkey_capture":
                    if not self._is_running():
                        self.capture_current()
        except queue.Empty:
            pass
        self.root.after(50, self._poll_events)

    def _update_mouse_pos(self):
        x, y = (int(v) for v in self.mouse_ctl.position)
        self.pos_var.set(f"마우스 위치: ({x}, {y})")
        self.root.after(100, self._update_mouse_pos)

    def _start_hotkeys(self):
        def on_press(key):
            if key == HOTKEY_TOGGLE:
                self.events.put(("hotkey_toggle", None))
            elif key == HOTKEY_CAPTURE:
                self.events.put(("hotkey_capture", None))

        self.kb_listener = keyboard.Listener(on_press=on_press)
        self.kb_listener.daemon = True
        self.kb_listener.start()

    # ---------------------------------------------------------- save/load
    def save(self):
        path = filedialog.asksaveasfilename(
            defaultextension=".json", filetypes=[("JSON", "*.json")], title="설정 저장"
        )
        if not path:
            return
        data = {
            "points": self.points,
            "repeat": self.repeat_var.get(),
            "loop_delay": self.loop_delay_var.get(),
            "start_delay": self.start_delay_var.get(),
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        self.status_var.set(f"저장됨: {path}")

    def load(self):
        if self._is_running():
            return
        path = filedialog.askopenfilename(filetypes=[("JSON", "*.json")], title="설정 불러오기")
        if not path:
            return
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            points = []
            for p in data["points"]:
                click = p.get("click", "왼쪽")
                if click not in CLICK_TYPES:
                    click = "왼쪽"
                points.append(
                    {"x": int(p["x"]), "y": int(p["y"]), "click": click, "delay": max(0, int(p.get("delay", 1000)))}
                )
        except Exception as e:
            messagebox.showerror("불러오기 실패", f"파일을 읽을 수 없습니다.\n{e}")
            return
        self.points = points
        self.repeat_var.set(str(data.get("repeat", "0")))
        self.loop_delay_var.set(str(data.get("loop_delay", "0")))
        self.start_delay_var.set(str(data.get("start_delay", "1000")))
        self._refresh_tree()
        self.status_var.set(f"불러옴: {path}")

    def on_close(self):
        if self._is_running():
            self.worker.stop()
        self.kb_listener.stop()
        self.root.destroy()


def main():
    enable_dpi_awareness()
    root = tk.Tk()
    AutoClickerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
