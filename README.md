# 🚗 RoadEyeCam

안드로이드 블랙박스(대시캠) 앱입니다. **Kotlin + CameraX + Material 3 + AdMob** 으로 만들었고,
Gradle Kotlin DSL 프로젝트로 바로 빌드할 수 있는 상태입니다.
앱 이름은 **RoadEyeCam** 이고, 한국어 · English · 日本語 · 中文 · Español 5개 언어를 지원합니다.

| 항목 | 값 |
| --- | --- |
| 앱 이름 | RoadEyeCam (`com.pickle.blackbox`) |
| 플랫폼 | Android (Kotlin) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| AGP / Gradle / Kotlin | 8.7.3 / 8.9 / 2.0.21 |
| CameraX | 1.4.1 (camera-core · camera2 · lifecycle · video · view) |
| UI | AndroidX + Material 3 (`Theme.Material3.*`), ViewBinding |
| 광고 | Google AdMob `play-services-ads:23.5.0` (테스트 ID 탑재) |
| 언어 | 한국어(기본) · English · 日本語 · 中文 · Español + 시스템 설정 |
| 백그라운드 녹화 | Foreground Service (`camera\|microphone\|location`) |
| CI | GitHub Actions — `.github/workflows/build-apk.yml` (디버그 APK 자동 빌드) |

---

## 1. 구현된 기능

### 1.1 백그라운드 녹화 — 앱을 나가도, 화면을 꺼도 계속
- 녹화는 **`RecordingService`(포그라운드 서비스)** 안에서 돌아갑니다. CameraX 의 `Preview` + `VideoCapture`
  유스케이스를 Activity 가 아니라 **서비스의 생명주기**에 바인딩했기 때문에, 홈 버튼을 누르거나 다른 앱으로
  전환하거나 화면을 꺼도 **녹화 세션과 구간 루프가 끊기지 않습니다.**
- 녹화 중에는 상단 알림 영역에 **"RoadEyeCam 녹화 중"** 상시 알림이 표시됩니다.
  - 알림을 누르면 앱이 다시 열리고, **중지** 액션으로 바로 녹화를 멈출 수 있습니다.
  - 녹화 중에는 서비스가 `startForeground` 상태라 시스템이 앱을 정리하지 않습니다.
- 앱으로 돌아오면 미리보기만 서비스의 카메라 세션에 다시 연결합니다. **재바인딩이 아니므로 녹화는 그대로**입니다.
- 서비스는 화면이 붙어 있지 않고 녹화도 없으면 스스로 종료하며 카메라를 놓아줍니다.

### 1.2 전면/후면 카메라 선택·전환 (녹화 중에도 전환 가능)
- `CameraSelector.DEFAULT_BACK_CAMERA` ↔ `DEFAULT_FRONT_CAMERA` 를 버튼 한 번으로 전환합니다.
- **녹화 중에 눌러도 됩니다.** 현재 구간을 정상 마감(저장)한 뒤 다른 렌즈로 재바인딩하고,
  **같은 세션의 다음 구간으로 자동 재개**합니다. 세션 타이머·상시 알림·잠금 설정은 유지됩니다.
  (`RecordingController.pauseForCameraSwitch()` → `CameraController.switchCamera()` → `RecordingController.resume()`)
- 요청한 렌즈가 없는 기기에서는 안전한 쪽으로 되돌아가고 안내 메시지를 보여줍니다.
- 화질: FHD 우선, 미지원 시 `FallbackStrategy.higherQualityOrLowerThan(SD)` 로 자동 폴백합니다.

### 1.3 화면 회전 (세로/가로)
- 두 Activity 모두 화면 방향 고정이 없어 **기기를 돌리면 자동으로 회전**합니다.
- `configChanges` 로 회전을 직접 처리하므로 Activity 가 재생성되지 않고, 미리보기와 녹화가 끊기지 않습니다.
- 현재 화면 방향은 서비스에 전달되어 **새로 시작하는 구간의 영상 방향**에 반영됩니다.
  (이미 녹화 중인 구간은 시작할 때의 방향을 유지합니다.)
- **가로 화면에서 녹화 중일 때는 하단 광고 배너를 자동으로 숨깁니다.** 배너가 미리보기와 조작 버튼을
  가리지 않도록, 세로로 돌리거나 녹화를 멈추면 다시 나타납니다(`MainActivity.updateAdBanner()`,
  `onConfigurationChanged` 에서 즉시 반영).

### 1.4 블랙박스 루프 녹화
- 녹화를 **구간(1분 / 3분 / 5분)** 으로 쪼개 저장하고, 구간이 끝나면 자동으로 다음 구간을 이어서 녹화합니다.
- **저장 용량 한도(1GB / 2GB / 5GB)** 를 넘으면 가장 오래된 *일반* 영상을 자동 삭제합니다.
- 저장 위치: **`Movies/PickleBlackbox`** (MediaStore). 갤러리·파일 탐색기에서 바로 재생됩니다.
  - Android 10(API 29)+ : `MediaStore.Video` + `RELATIVE_PATH` + `IS_PENDING`
  - Android 9(API 28)- : 공용 `Movies/PickleBlackbox` 폴더 + `MediaScanner`
- 촬영 중에는 임시 파일(`Android/data/<패키지>/files/segments`)에 쓰고, 구간이 끝나면 MediaStore로 게시한 뒤
  임시 파일을 지웁니다. 비정상 종료로 남은 임시 파일은 다음 실행 때 정리합니다.

### 1.5 충격 감지(G-센서) + 수동 긴급 잠금
- `SensorManager` 가속도 센서로 합성 가속도를 계산해 임계값(기본 2.6G)을 넘으면 충격으로 판단합니다(2초 쿨다운).
  센서 감지는 서비스에서 돌기 때문에 **화면이 꺼져 있어도 동작**합니다.
- 충격이 감지되면 그 순간 녹화 중인 구간이 **잠금 상태로 저장**됩니다.
- 수동 잠금 버튼: 녹화 중이면 현재 구간, 아니면 가장 최근 영상을 잠급니다.
- 잠금 영상은 파일명 앞에 **`LOCKED_`** 가 붙고, 용량 정리에서 **절대 삭제되지 않습니다.**

### 1.6 HUD
- 실시간 녹화 타이머, 다음 구간 전환까지 남은 시간, 날짜·시간(초 단위)
- 렌즈 배지(`전면 카메라` / `후면 카메라`), GPS 속도(km/h), 저장 사용량 / 한도
- 잠금 상태 배지, 녹화 상태 표시(REC 점멸), 녹화 중 화면 꺼짐 방지(`keepScreenOn`)

### 1.7 다국어 (한국어 · English · 日本語 · 中文 · Español)
- `설정 → 앱 언어` 에서 **시스템 설정 / 한국어 / English / 日本語 / 中文 / Español** 중 선택할 수 있습니다.
- 선택은 `SharedPreferences`(`AppSettings.languageTag`)에 저장되고,
  **`AppCompatDelegate.setApplicationLocales()`** 로 앱 전체(화면·다이얼로그·토스트·상시 알림)에 즉시 적용됩니다.
- **처음 설치 후 첫 실행 때 언어 선택 화면이 한 번 뜹니다.** `한국어 / English / 日本語 / 中文 / Español /
  시스템 설정` 중 하나를 누르면 곧바로 그 언어로 앱이 바뀌고, 선택은 저장되어 다시 묻지 않습니다
  (`AppSettings.hasSelectedLanguage`). 이후에는 `설정 → 앱 언어` 에서 언제든 바꿀 수 있습니다.
- 문자열은 `res/values/strings.xml`(한국어 기본) 과 `values-en`, `values-ja`, `values-zh`, `values-es` 에 있습니다.
- 언어 이름은 각 언어 표기 그대로 두어, 지금 화면 언어를 몰라도 찾을 수 있게 했습니다.

### 1.8 녹화 목록 / 재생
- `GalleryActivity` + `RecyclerView` 로 저장된 클립 목록(썸네일·시간·길이·용량·잠금 배지)을 보여줍니다.
- 항목을 누르면 `VideoView` + `MediaController` 로 바로 재생, 잠금 토글·삭제(확인 다이얼로그)도 지원합니다.

### 1.9 AdMob 수익화
- 앱 ID: `AndroidManifest.xml` 의 `com.google.android.gms.ads.APPLICATION_ID` (테스트 ID)
- 배너: **메인 화면 하단**, **갤러리 화면 하단** (앵커드 적응형 배너)
- 전면광고: **녹화 정지 직후**, **설정/갤러리 열기 전** — 3분 쿨다운(`AdConfig.INTERSTITIAL_COOLDOWN_MS`).
  설정·갤러리를 열어도 녹화는 백그라운드에서 계속되므로 광고가 녹화를 끊지 않습니다.
- **가로 화면에서 녹화 중에는 메인 배너를 숨깁니다**(광고 갱신도 일시정지). 세로 복귀 또는 녹화 정지 시 다시 표시됩니다.
- 실제 ID로 바꾸는 위치는 **3장**에 정리되어 있고, 코드에는 `TODO(AdMob)` 주석이 달려 있습니다.

### 1.10 GitHub Actions 자동 APK 빌드
- `.github/workflows/build-apk.yml`: `actions/checkout@v4` → `setup-java`(JDK 17) → `setup-gradle`(8.9)
  → wrapper 확인/생성 → `./gradlew assembleDebug` → `pickle-blackbox-debug-apk` 아티팩트 업로드.
- `main` 푸시 또는 Actions 탭의 **Run workflow** 로 실행됩니다.
- 워크플로 파일은 저장소의 GitHub App 연결에 **Workflows 쓰기 권한**이 있어야 푸시할 수 있습니다.

---

## 2. 빌드하고 실행하기

### 2.1 Android Studio (가장 쉬움)
1. Android Studio (Ladybug 이상) 에서 **Open** → 이 폴더(`blackbox-app`) 선택
2. 안내에 따라 **Android SDK 35** 설치
3. 실행 대상 기기(실제 폰 권장 — 에뮬레이터는 카메라 동작이 제한적) 선택 → **Run ▶**

### 2.2 터미널
```bash
# JDK 17 필요 (java -version 으로 확인)
cd blackbox-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS 예시
./gradlew assembleDebug --offline
# 결과: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
> `local.properties` 는 `.gitignore` 에 포함되어 있습니다. 커밋하지 마세요.
> Gradle 래퍼(`gradlew`, `gradle/wrapper/gradle-wrapper.jar`)가 포함되어 있어 별도 Gradle 설치 없이 빌드됩니다.

### 2.3 디버그 빌드 정보
- 패키지명: `com.pickle.blackbox.debug` (정식 빌드와 함께 설치 가능)
- 앱 이름: **RoadEyeCam**
- 서명: 디버그 키 자동 서명 (Play 업로드는 4장 참고)

---

## 3. ⚠️ AdMob 실제 ID로 교체하기 (출시 전 필수)

지금 들어 있는 값은 **구글 공식 테스트 ID** 입니다. 테스트 광고는 항상 채워지지만 **수익은 0원**입니다.
아래 3곳만 바꾸면 됩니다.

### 3.1 App ID → `AndroidManifest.xml`
```xml
<!-- TODO(AdMob): replace with your real AdMob App ID -->
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-3940256099942544~3347511713" />
```
→ AdMob 콘솔 **앱 설정 > 앱 ID**(`~` 구분자)로 교체. 형식이 틀리면 앱이 시작하자마자 종료됩니다.

### 3.2 광고 단위 ID → `AdConfig.kt`
```kotlin
// TODO(AdMob): replace with your real Banner ad unit ID.
const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

// TODO(AdMob): replace with your real Interstitial ad unit ID.
const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
```
- 광고를 잠시 끄고 싶으면 같은 파일의 `ADS_ENABLED = false`.

### 3.3 테스트 기기 등록 (무효 트래픽 방지)
실제 ID로 바꾼 뒤 본인 폰에서 광고를 반복 클릭하면 무효 트래픽으로 계정이 정지될 수 있습니다.
```kotlin
MobileAds.setRequestConfiguration(
    RequestConfiguration.Builder().setTestDeviceIds(listOf("YOUR_TEST_DEVICE_ID")).build()
)
```

### 3.4 app-ads.txt
1. `docs/app-ads.txt` 의 `pub-0000000000000000` 을 본인 게시자 ID로 바꿉니다.
2. 개발자 웹사이트 **루트**에 `https://내도메인.com/app-ads.txt` 로 업로드합니다(스토어 등록정보의 개발자 웹사이트와 같은 도메인).
3. AdMob 콘솔 **앱 > app-ads.txt** 에서 확인(반영까지 최대 24시간).

---

## 4. Play 스토어 출시 단계

1. **서명 키 생성**
   ```bash
   keytool -genkeypair -v -keystore roadeyecam-upload.jks \
     -alias roadeyecam -keyalg RSA -keysize 2048 -validity 10000
   ```
   `*.jks` 는 `.gitignore` 로 커밋되지 않습니다. 분실하면 업데이트가 불가능합니다.
2. **`app/build.gradle.kts` 의 release 서명 설정** (`TODO(PlayStore)` 부분) + `keystore.properties`(gitignore 대상)
3. `versionCode` / `versionName` 증가 (현재 5 / 1.2.1)
4. **AAB 빌드** → `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
5. **Play Console**
   - 앱 콘텐츠: 카메라·마이크·위치·알림 권한 사용 목적 설명
   - **포그라운드 서비스 선언**: `camera|microphone|location` 타입과 녹화 목적 기재(Play 정책상 필수 항목)
   - **광고 포함** 여부: 예 (AdMob)
   - **데이터 안전성**: 동영상·대략적 위치 저장, 광고 ID 사용(AdMob) 신고
   - **개인정보처리방침 URL** 필수(광고 ID·영상·위치 처리 고지)
6. 내부 테스트 → 비공개 테스트(12명·14일) → 프로덕션 출시

---

## 5. 저장 구조와 파일 이름

| 구분 | 경로 / 규칙 |
| --- | --- |
| 공개 저장소 | `Movies/PickleBlackbox/` (MediaStore) |
| 일반 영상 | `BB_20261122_215830.mp4` (촬영 시작 시각) |
| 잠금 영상 | `LOCKED_BB_20261122_215830.mp4` (용량 정리 제외) |
| 임시 파일 | `Android/data/com.pickle.blackbox/files/segments/seg_<millis>.mp4` (게시 후 삭제) |
| 설정 | `SharedPreferences` → `pickle_blackbox_settings` |

용량 정리는 **잠금이 아닌** 영상만 오래된 순서로 삭제하며, 잠금 영상만으로 한도를 넘으면 잠금 영상을 유지하고
로그만 남깁니다(사용자가 잠근 영상을 임의로 지우지 않기 위함).

---

## 6. 프로젝트 구조

```
blackbox-app/
├── settings.gradle.kts              # 모듈 구성
├── build.gradle.kts                 # 플러그인 선언
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml           # 버전 카탈로그
│   └── wrapper/                     # gradle-wrapper.jar / .properties (Gradle 8.9)
├── gradlew / gradlew.bat
├── .github/workflows/build-apk.yml  # 푸시 시 디버그 APK 자동 빌드
├── docs/
│   ├── app-ads.txt                  # AdMob app-ads.txt 템플릿
│   └── release-checklist.md         # 출시 체크리스트
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/pickle/blackbox/
        │   ├── BlackboxApp.kt               # Application: 설정/저장소/광고 싱글턴 + 앱 언어 적용
        │   ├── ads/
        │   │   ├── AdConfig.kt              # ★ 실제 광고 단위 ID 교체 위치
        │   │   └── AdManager.kt             # 배너 + 전면광고(쿨다운)
        │   ├── camera/
        │   │   ├── CameraController.kt      # CameraX 바인딩 / 전·후면 전환 / 화면 방향
        │   │   └── RecordingController.kt   # 구간 루프 녹화 / 잠금 / 카메라 전환 시 일시정지·재개
        │   ├── location/SpeedTracker.kt     # GPS 속도(권한 안전)
        │   ├── media/
        │   │   ├── Clip.kt                  # 클립 모델
        │   │   ├── ClipStorage.kt           # MediaStore 게시·조회·삭제·용량 정리
        │   │   └── VideoThumbs.kt           # 목록 썸네일
        │   ├── sensor/ImpactDetector.kt     # 가속도 센서 충격 감지
        │   ├── service/
        │   │   └── RecordingService.kt      # ★ 포그라운드 서비스: 백그라운드 녹화 + 상시 알림
        │   ├── settings/AppSettings.kt      # 사용자 설정(언어 포함)
        │   ├── ui/
        │   │   ├── MainActivity.kt          # HUD / 녹화 제어 / 설정 (서비스에 연결)
        │   │   ├── GalleryActivity.kt       # 목록 · 재생 · 삭제
        │   │   └── ClipAdapter.kt
        │   └── util/
        │       ├── AppLanguage.kt           # 앱 언어 선택/적용
        │       └── Formatters.kt
        └── res/
            ├── values/ (colors, strings[ko], themes)
            ├── values-en/ values-ja/ values-zh/ values-es/   # 번역 문자열
            ├── layout/ (activity_main, activity_gallery, item_clip, dialog_settings, dialog_player)
            ├── drawable/ (아이콘 · HUD 배경)
            └── mipmap-anydpi-v26/ic_launcher.xml
```

---

## 7. 권한

| 권한 | 사용 목적 | 없을 때 |
| --- | --- | --- |
| `CAMERA` | 프리뷰·녹화 | 녹화 불가(안내 패널 표시) |
| `RECORD_AUDIO` | 소리 함께 녹음 + FGS microphone 타입 | 무음으로 녹화 |
| `ACCESS_FINE_LOCATION` | HUD 속도 표시 + FGS location 타입 | `GPS 대기` 표시, 앱은 정상 |
| `POST_NOTIFICATIONS` (API 33+) | 녹화 중 상시 알림 표시 | 녹화는 되지만 알림이 보이지 않음 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` / `_MICROPHONE` / `_LOCATION` | 백그라운드(화면 꺼짐) 녹화 | 서비스 시작 불가 |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | 구형 기기에서 `Movies/PickleBlackbox` 쓰기 | 저장 실패 안내 |
| `INTERNET`, `ACCESS_NETWORK_STATE` | AdMob 광고 | 광고만 미표시 |

> 서비스는 `foregroundServiceType="camera|microphone|location"` 으로 선언되어 있고, 실행 시에는
> **실제로 허용된 권한의 타입만** 넘겨 `startForeground` 합니다(Android 14+ 의 타입/권한 검사 대응).

---

## 8. 설정 항목

| 항목 | 값 | 기본값 |
| --- | --- | --- |
| 앱 언어 | 시스템 설정 / 한국어 / English / 日本語 / 中文 / Español | 시스템 설정 |
| 녹화 구간 길이 | 1 / 3 / 5 분 | 3분 |
| 저장 용량 한도 | 1 / 2 / 5 GB | 2GB |
| 충격 감도 | 1(둔함, 3.4G) ~ 5(예민, 1.9G) | 3 (2.6G) |
| 충격 감지 시 자동 잠금 | on/off | on |
| 소리 함께 녹음 | on/off | on |
| GPS 속도 표시 | on/off | on |

녹화 관련 설정 변경은 **다음 녹화 구간부터** 적용됩니다(현재 구간은 이어서 저장). 언어 변경은 즉시 적용됩니다.

---

## 9. 알려진 제한과 다음 단계

- **녹화 중 회전**: 이미 녹화 중인 구간은 시작 당시 방향을 유지하고, 회전 이후 시작되는 다음 구간부터 새 방향이 적용됩니다.
- **녹화 중 카메라 전환**: 현재 구간을 마감하고 새 구간으로 이어지므로, 전환 순간에 짧은 공백(수백 ms)이 생깁니다.
- **배터리/발열**: 화면이 켜져 있으면 `keepScreenOn` 으로 화면을 유지합니다. 장시간 주차 녹화에는 전원 연결을 권장합니다.
- **안드로이드 14+ 백그라운드 시작 제한**: 서비스는 항상 앱이 화면에 있을 때 시작되며, 시스템이 강제로 종료하면 자동 재시작하지 않습니다(`START_NOT_STICKY`).
- 서비스는 알림의 **중지** 로 즉시 끝낼 수 있습니다.

---

## 10. 트러블슈팅

| 증상 | 확인 |
| --- | --- |
| 앱이 시작하자마자 종료 | `AndroidManifest.xml` 의 AdMob App ID 형식(`~` 포함) 확인 |
| 녹화 중 알림이 안 보임 | 알림 권한(POST_NOTIFICATIONS) 허용 여부 |
| 녹화가 계속되고 있음 | 상시 알림의 **중지** 를 누르거나 앱에서 정지 |
| `SDK location not found` | `local.properties` 에 `sdk.dir=...` 또는 `ANDROID_HOME` 설정 |
| 빌드는 되는데 설치 실패 | 디버그는 `com.pickle.blackbox.debug` 로 설치됩니다(서명 충돌 시 기존 앱 삭제) |
| 저장된 영상이 안 보임 | 권한 허용 여부, 갤러리 앱 새로고침. 파일은 `Movies/PickleBlackbox` |
| 광고가 안 뜸 | 인터넷 연결, 테스트 ID는 즉시 채워짐(빈 화면이면 SDK 초기화 로그 확인) |
| 충격 감지가 너무 예민/둔함 | 설정 → 충격 감도 (1~5) 조정 |
| 앱 언어가 바뀌지 않음 | 설정 → 앱 언어에서 다시 선택(즉시 적용) |

---

## 11. 라이선스 / 고지

- 이 프로젝트는 사용자가 자유롭게 수정·배포할 수 있도록 작성된 샘플 앱입니다.
- AdMob, Google Play 서비스, CameraX, AndroidX, Material Components 는 각 제공자의 라이선스를 따릅니다.
- 실제 배포 시 개인정보처리방침에 **광고 ID 수집(AdMob)** 과 **영상·위치 데이터 처리** 를 명시해야 합니다.
