# 멀티 포인트 오토 클리커

여러 화면 위치를 순서대로, 지정한 간격으로 반복 클릭하는 앱입니다.

- **안드로이드 휴대폰**: `android/` (설치 파일: [Releases](https://github.com/akskekekdk/autoclicker/releases/latest)의 `AutoClicker-v*.apk`)
- **PC** (Windows / macOS / Linux X11): `auto_clicker.py`

---

## 📱 안드로이드 앱

### 설치
1. [최신 Release](https://github.com/akskekekdk/autoclicker/releases/latest)에서 APK를 받아 설치합니다. ("출처를 알 수 없는 앱" 설치 허용 필요)
2. 앱을 열고 **접근성 설정 열기** → 설치된 앱 → **오토 클리커** → 사용
   - Android 13 이상에서 회색으로 막혀 있으면: 설정 → 앱 → 오토 클리커 → 우측 상단 ⋮ → **제한된 설정 허용** 후 다시 켭니다.
3. 접근성을 켜면 화면 왼쪽에 **플로팅 패널**이 나타납니다.

### 사용 방법
| 버튼 | 기능 |
|---|---|
| ⠿ | 끌어서 패널 이동 |
| ▶ / ■ | 시작 / 정지 |
| ＋ | 터치 위치 추가 → 번호가 붙은 빨간 원을 원하는 곳으로 끌어다 놓기 |
| － | 마지막 위치 삭제 |
| ⚙ | 설정 화면 (포인트별 대기 시간, 반복 횟수, 반복 사이 대기) |
| ▁ | 패널 줄이기 → 작은 동그라미로 바뀜. 탭하면 다시 펼치고, 끌면 이동 (실행 중에도 사용 가능) |
| ✕ | 패널 닫기 (앱에서 다시 열 수 있음) |

- 번호 순서대로 터치하고, 각 포인트의 **대기(ms)** 만큼 기다린 뒤 다음 포인트를 터치합니다.
- 반복 횟수 0 = 무한 반복. 실행 중에는 원이 반투명해지고 터치가 통과되며, 현재 터치 중인 원이 초록색으로 표시됩니다.
- 설정과 위치는 자동 저장됩니다.
- 루팅 없이 동작하며 Android 7.0 이상 지원. **iPhone(iOS)은 앱이 다른 앱 화면을 터치하는 것을 허용하지 않아 지원할 수 없습니다.**

### 앱 내 업데이트
앱 설정 화면 맨 위 **앱 업데이트**에서 새 버전을 확인하고 바로 설치할 수 있습니다.
`android/` 코드가 바뀌어 푸시되면 GitHub Actions(`.github/workflows/android-release.yml`)가 서명된 APK를 빌드해 Release `v<번호>`로 올립니다.

서명 키는 저장소 Secrets에 있어야 합니다 (Settings → Secrets and variables → Actions):
- `KEYSTORE_BASE64`: PKCS12 키 파일(별칭 `autoclicker`)을 base64로 인코딩한 값
- `KEYSTORE_PASSWORD`: 키 비밀번호

키를 바꾸면 이미 설치된 앱은 업데이트되지 않으니(삭제 후 재설치 필요) 키 파일은 안전하게 보관하세요.

### 직접 빌드
```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

---

## 💻 PC 버전

## 기능
- **여러 포인트 등록**: X, Y 좌표를 직접 입력하거나 마우스 위치로 바로 추가
- **포인트별 설정**: 클릭 종류(왼쪽 / 오른쪽 / 가운데 / 더블)와 **클릭 후 대기 시간(ms)**, 즉 다음 포인트까지의 간격
- **반복 설정**: 반복 횟수(0 = 무한), 한 바퀴가 끝난 뒤 반복 사이 대기 시간, 시작 지연
- **전역 단축키**
  - `F6`: 현재 마우스 위치를 포인트로 추가
  - `F9`: 시작 / 정지
- 포인트 순서 변경(▲/▼), 수정, 삭제
- 설정 **저장/불러오기** (JSON)
- 실행 중에는 현재 포인트가 목록에 강조 표시됨
- 창 "항상 위" 옵션

## 실행 방법
```bash
pip install -r requirements.txt
python auto_clicker.py
```
> Python 3.8 이상 필요 (tkinter 포함). Linux에서는 `sudo apt install python3-tk`가 필요할 수 있습니다.

### Windows용 exe 만들기 (선택)
```bash
pip install pyinstaller
pyinstaller --onefile --noconsole --name AutoClicker auto_clicker.py
```
`dist/AutoClicker.exe`가 생성됩니다.

## 사용 예
1. 클릭하고 싶은 위치에 마우스를 올리고 `F6` (첫 번째 포인트)
2. 다른 위치에서 다시 `F6` (두 번째 포인트)
3. 목록에서 포인트를 선택하고 "클릭 후 대기(ms)"를 바꾼 뒤 **선택 항목 수정**
   - 예: 1번 포인트 대기 500 → 0.5초 후 2번 포인트 클릭
4. 반복 횟수와 반복 사이 대기를 정한 뒤 `F9`로 시작, 다시 `F9`로 정지

## 참고
- macOS: 시스템 설정 → 개인정보 보호 및 보안 → **손쉬운 사용**, **입력 모니터링**에서 터미널(또는 Python)에 권한을 허용해야 클릭과 단축키가 동작합니다.
- Windows 화면 배율(125%/150%)을 쓰는 환경에서도 좌표가 맞도록 DPI 인식을 켜 둡니다.
- 관리자 권한으로 실행 중인 프로그램을 클릭하려면 이 앱도 관리자 권한으로 실행해야 할 수 있습니다.
