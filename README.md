# 🚗 RoadEyeCam

안드로이드 블랙박스(대시캠) 앱입니다. **Kotlin + CameraX + Material 3 + AdMob** 으로 만들었고,
Gradle Kotlin DSL 프로젝트로 바로 빌드할 수 있는 상태입니다.
앱 이름은 **RoadEyeCam** 이고, 한국어 · English · 日本語 · 中文 · Español 5개 언어를 지원합니다.

| 항목 | 값 |
| --- | --- |
| 앱 이름 | RoadEyeCam (`com.pickle.blackbox`) |
| 버전 | 1.2.8 (versionCode 12) |
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
- 앱이 백그라운드로 가면(`MainActivity.onStop()`) 화면의 프리뷰 서피스는 사라지는데, 이때 `Preview` 유스케이스를
  서피스 없이 두면 일부 기기(Samsung Camera2 HAL)에서는 반복 캡처 요청이 멈춰 `VideoCapture` 로 프레임이
  들어오지 않고 **녹화 영상이 정지 화면으로 저장**됩니다. 그래서 서비스는 화면이 없을 때
  **오프스크린 서피스(`ImageReader`, PRIVATE)** 를 프리뷰 소비자로 붙여 두고, 화면이 돌아오면 실제 프리뷰
  서피스로 교체합니다(`CameraController.setPreviewSurfaceProvider`, `DummyPreviewSurface`).
- 화면이 꺼진 상태에서도 CPU 가 잠들지 않도록 녹화 중에는 **`PARTIAL_WAKE_LOCK`**(4시간 안전 타임아웃, 3시간마다
  갱신)을 잡습니다. 녹화가 끝나거나 서비스가 종료되면 즉시 해제합니다.
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
- **방향은 화면이 아니라 기기가 실제로 놓인 자세를 따릅니다.** 자동 회전을 꺼 두었거나(차량 거치 상태에서 흔함)
  세로 고정 앱이 앞에 있으면 `Display.rotation` 이 세로(`ROTATION_0`)로 남아 영상이 세로로 저장될 수 있어,
  `RecordingService` 가 **`OrientationEventListener`(가속도 센서)** 로 기기 각도를 읽어 `ROTATION_0/90/180/270`
  으로 매핑합니다(±35° 이내일 때만 반영, 평평할 때는 마지막 값 유지). 센서를 쓸 수 없을 때만 화면 회전을 씁니다.
  서비스가 만들어지는 시점에는 `DisplayManager` 로 읽은 화면 회전을 초기값으로 씁니다(`onCreate`).
- **화면 회전도 서비스가 직접 감시합니다 (1.2.7).** `RecordingService` 가 `DisplayManager.registerDisplayListener`
  로 `DEFAULT_DISPLAY` 를 지켜보므로, 앱이 백그라운드에 있어도 자동 회전으로 폰이 돌아가거나 내비 앱이 화면을
  가로로 돌리면 그 즉시 방향이 반영됩니다(이전에는 `MainActivity` 가 살아 있을 때만 화면 회전을 받았습니다).
  화면이 **가로로 바뀐** 경우에는 센서가 아직 세로를 읽고 있어도(거치대 기울기가 큰 경우 등) 화면 회전을 믿고,
  화면이 세로(`ROTATION_0`)에 고정된 경우에는 지금처럼 센서가 이깁니다. 세로 거치대에서 폰만 가로로 돌려도 가로로 찍힙니다.
- **`녹화 방향` 설정 (1.2.7): 자동 / 가로 고정.** 가로 고정을 켜면 세로로 녹화를 시작하든 백그라운드에 있든
  항상 16:9 가로로만 저장합니다(`targetRotation` 을 `ROTATION_90`, 센서가 `ROTATION_270` 을 읽으면 `ROTATION_270` 으로 고정).
  설정에서 바꾸면 녹화 중이어도 즉시 반영됩니다.
- **녹화를 시작하는 순간 기기가 이미 가로이면 첫 구간부터 가로입니다.** `beginRecording()` 이 마지막 센서 각도를
  즉시 적용(`refreshOrientationNow()`)하므로, 센서 콜백이 아직 오지 않았거나 애매한 각도였어도 처음부터 맞게 찍힙니다.
- **방향이 바뀌면 카메라 세션을 다시 바인딩합니다 (1.2.6).** 실행 중인 `Recorder`/`VideoCapture` 에는
  `targetRotation` 만 바꿔서는 새 방향이 반영되지 않습니다(Samsung CameraX 1.4.1: 인코더 서피스·영상 회전 정보가
  바인딩 당시 값에 고정 → 세로(9:16) 크기에 가로 화면이 눌린 영상이 저장됨). 그래서 `CameraController.rebind()` 가
  새 `Recorder` + `VideoCapture.Builder(recorder).setTargetRotation(...)` + `Preview.Builder().setTargetRotation(...)`
  으로 세션을 다시 만듭니다.
  - 녹화 중이 아니면 방향이 바뀌는 즉시 재바인딩합니다(다음 구간부터 올바른 방향).
  - 녹화 중이면 **4.5초** 디바운스 뒤 현재 구간을 마감·저장하고(`RecordingController.pauseForRebind()`) 재바인딩한 다음
    같은 세션의 다음 구간으로 이어서 녹화합니다(`RecordingService.rebindForRotation()` → `onReady` → `resume()`).
    세션(타이머·상시 알림)은 끊기지 않고, 구간 경계에서 짧은 공백만 생깁니다.
  - 재바인딩 중에 카메라 전환 버튼을 눌러도 요청이 사라지지 않습니다. 한 번의 재바인딩이 방향과 렌즈를 함께 적용합니다.
- **녹화 중에 기기를 돌리면 그 시점에서 구간을 끊고 새 방향으로 이어서 찍습니다 (1.2.5, 1.2.6 에서 재바인딩 추가).** 방향이 바뀐 뒤
  **4.5초(1.2.7 에서 1.2초 → 4.5초)** 유지되면 현재 구간을 마감·저장하고 곧바로 새 방향 구간을 시작합니다(`RecordingService` 디바운스
  `ORIENTATION_RESTART_DEBOUNCE_MS = 4500`). 잠깐 기울이거나 들고 걸을 때 1~2초짜리 영상으로 쪼개지지 않습니다.
  **`가로 고정` 모드에서는 방향 때문에 구간을 끊지 않습니다** — 설정한 1/3/5분 길이로만 나뉩니다
  (설정에서 녹화 방향을 직접 바꾼 경우에만 한 번 분할해 즉시 반영).
- **전면/후면 어느 렌즈든 가로 녹화가 됩니다.** (1.2.4 에서 `VideoCapture` 를 만들 때 `setTargetRotation` 을
  넘기고, 렌즈 전환 시에도 방향이 유지되도록 수정. 1.2.6 에서 방향 변경 시 세션을 재바인딩하도록 수정 —
  이전에는 전면으로 바꾸거나 세로로 시작한 세션이 9:16 세로로 저장됐습니다.)
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

### 1.5 수동 긴급 잠금
- 수동 잠금 버튼: 녹화 중이면 현재 구간, 아니면 가장 최근 영상을 잠급니다.
- (1.2.3 에서 **충격 감지(G-센서) 자동 잠금은 제거**했습니다. `sensor/ImpactDetector.kt`, 충격 감도·자동 잠금
  설정과 관련 문자열·레이아웃이 모두 삭제되어, 이제 클립 잠금은 사용자가 버튼으로만 할 수 있습니다.)
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

### 1.11 녹화 영상 날짜·시간 워터마크 (1.2.8)
- **저장된 MP4 자체에 날짜·시간이 찍힙니다.** `yyyy-MM-dd HH:mm:ss`(예: `2026-11-24 05:33:38`)가
  화면 아래쪽 왼쪽에 반투명 검정 배지 + 흰색 굵은 글씨로 들어가고, 갤러리·다른 앱·PC로 복사한
  파일에서도 그대로 보입니다. 속도(km/h)는 넣지 않았습니다.
- 구현은 Google `camera-effects` 라이브러리 없이 **직접 만든 EGL 2.0 / GLES20 `SurfaceProcessor`**
  입니다(`camera/TimestampWatermark.kt`). `CameraEffect(VIDEO_CAPTURE)` 로 **비디오 스트림에만** 붙기
  때문에 미리보기 화면은 그대로이고, 녹화 파이프라인에 한 번 더 그려 넣는 방식이라 별도 트랜스코딩이
  없습니다.
  - 카메라 프레임은 `SurfaceOutput.updateTransformMatrix()` 가 돌려주는 텍스처 행렬로 그립니다.
    이 행렬에는 이 유스케이스의 **회전·미러링·크롭**이 모두 들어 있어서, 전면/후면 · 세로/가로
    (`ROTATION_90`/`270`) 어디서든 영상이 바로 선 상태로 인코더에 들어갑니다.
  - 날짜·시간은 그 위에 **축이 맞춰진 별도 사각형**으로 그리므로 영상이 회전해도 글자가 눕거나
    뒤집히지 않고, 미러링되어도 좌우 반전되지 않습니다.
  - 텍스트 비트맵은 **초가 바뀔 때만**(+프레임 크기가 바뀔 때) 다시 그리고 `glTexSubImage2D` 로
    업로드합니다. 30fps 에서도 프레임당 비트맵/텍스처 할당이 없습니다.
  - 서비스 종료·재바인딩(렌즈 전환, 회전) 시 `SurfaceTexture`·EGL 서피스·컨텍스트·GL 오브젝트·
    GL 스레드를 순서대로 해제합니다(`TimestampWatermarkEffect.close()`).
  - 기기가 효과를 거부하면(=바인드 예외) **효과 없이 다시 바인드**해서 카메라는 항상 살아 있게 합니다.

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
3. `versionCode` / `versionName` 증가 (현재 12 / 1.2.8)
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
        │   │   ├── CameraController.kt      # CameraX 바인딩 / 전·후면 전환 / 화면 방향 / 오프스크린 프리뷰 서피스
        │   │   └── RecordingController.kt   # 구간 루프 녹화 / 잠금 / 카메라 전환 시 일시정지·재개
        │   ├── location/SpeedTracker.kt     # GPS 속도(권한 안전)
        │   ├── media/
        │   │   ├── Clip.kt                  # 클립 모델
        │   │   ├── ClipStorage.kt           # MediaStore 게시·조회·삭제·용량 정리
        │   │   └── VideoThumbs.kt           # 목록 썸네일
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
| `WAKE_LOCK` | 화면이 꺼진 동안 녹화 파이프라인이 멈추지 않도록 CPU 유지 | 화면 꺼짐 시 프레임이 끊길 수 있음 |
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
| 녹화 방향 | 자동 / 가로 고정 | 자동 |
| 저장 용량 한도 | 1 / 2 / 5 GB | 2GB |
| 소리 함께 녹음 | on/off | on |
| GPS 속도 표시 | on/off | on |

녹화 관련 설정 변경은 **다음 녹화 구간부터** 적용됩니다(현재 구간은 이어서 저장). 언어 변경은 즉시 적용됩니다.

---

## 9. 알려진 제한과 다음 단계

- **녹화 중 회전**: 방향이 4.5초 이상 유지되면 그 시점에서 구간을 끊고, 세션을 재바인딩한 뒤 새 방향으로 이어서
  찍습니다(1.2.7). 짧게 흔들었다 원래대로 돌리면 구간은 그대로 이어집니다. 재바인딩 순간에 짧은 공백(수백 ms)이 생깁니다.
  가로 고정 모드에서는 방향 때문에 끊지 않습니다.
- **녹화 중 카메라 전환**: 현재 구간을 마감하고 새 구간으로 이어지므로, 전환 순간에 짧은 공백(수백 ms)이 생깁니다.
- **배터리/발열**: 화면이 켜져 있으면 `keepScreenOn` 으로 화면을 유지하고, 녹화 중에는 `PARTIAL_WAKE_LOCK` 을 잡습니다. 장시간 주차 녹화에는 전원 연결을 권장합니다.
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
| 백그라운드 녹화 영상이 정지 화면 | 1.2.3 에서 오프스크린 프리뷰 서피스 + `PARTIAL_WAKE_LOCK` 으로 수정. 최신 APK 설치 후 확인 |
| 전면 카메라 영상이 세로(9:16)로 저장 | 1.2.6 에서 방향 변경 시 세션 재바인딩(`CameraController.rebind()`)으로 수정. 1.2.4 의 `setTargetRotation` 만으로는 Samsung 에서 인코더 방향이 따라오지 않았습니다. 최신 APK 설치 후 확인 |
| 녹화 중 폰을 돌렸는데 영상이 계속 세로 | 1.2.6 에서 디바운스 후 구간 마감 + 세션 재바인딩으로 수정. 설치된 APK 가 1.2.6(versionCode 10)인지 확인 |
| 영상이 1~2초짜리로 계속 쪼개짐 | 1.2.7 에서 회전 디바운스를 4.5초로 늘리고, 가로 고정 모드에서는 방향으로 구간을 끊지 않도록 수정. 설치된 APK 가 1.2.7(versionCode 11)인지 확인 |
| 세로로 시작했거나 백그라운드에서 세로로 저장 | 1.2.7 의 `녹화 방향 = 가로 고정` 을 켜세요. 백그라운드 회전은 1.2.7 에서 `DisplayManager.DisplayListener` 로 감지합니다 |
| 영상에 날짜·시간이 안 보임 | 1.2.8(versionCode 12)인지 확인. `logcat` 에서 `TimestampWatermark` 태그의 `Output surface ready` / `EGL ready` / `Timestamp effect failed` 로그 확인. 효과가 바인드에 실패하면 앱이 효과 없이 다시 바인드하므로 녹화 자체는 정상입니다 |
| 날짜·시간 글자가 눕거나 뒤집혀 보임 | 드물게 인코더 서피스 방향이 기대와 다를 때 생깁니다. `TimestampWatermarkProcessor.drawStamp()` 의 `STAMP_TEX_COORDS` / 사각형 좌표를 90° 회전한 값으로 바꾸면 됩니다 |
| 앱 언어가 바뀌지 않음 | 설정 → 앱 언어에서 다시 선택(즉시 적용) |

---

## 11. 라이선스 / 고지

- 이 프로젝트는 사용자가 자유롭게 수정·배포할 수 있도록 작성된 샘플 앱입니다.
- AdMob, Google Play 서비스, CameraX, AndroidX, Material Components 는 각 제공자의 라이선스를 따릅니다.
- 실제 배포 시 개인정보처리방침에 **광고 ID 수집(AdMob)** 과 **영상·위치 데이터 처리** 를 명시해야 합니다.
