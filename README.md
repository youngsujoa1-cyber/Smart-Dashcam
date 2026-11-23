# 🚗 픽클 블랙박스 (Pickle Blackbox)

안드로이드 블랙박스(대시캠) 앱입니다. **Kotlin + CameraX + Material 3 + AdMob** 으로 만들었고,
Gradle Kotlin DSL 프로젝트로 바로 빌드할 수 있는 상태입니다.

| 항목 | 값 |
| --- | --- |
| 플랫폼 | Android (Kotlin) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| AGP / Gradle / Kotlin | 8.7.3 / 8.9 / 2.0.21 |
| CameraX | 1.4.1 (camera-core · camera2 · lifecycle · video · view) |
| UI | AndroidX + Material 3 (`Theme.Material3.*`), ViewBinding |
| 광고 | Google AdMob `play-services-ads:23.5.0` (테스트 ID 탑재) |
| CI | GitHub Actions — 푸시하면 디버그 APK 자동 빌드 |

---

## 1. 구현된 기능

### 1.1 전면/후면 카메라 선택·전환
- CameraX `Preview` + `VideoCapture(Recorder)` 를 하나의 `ProcessCameraProvider` 에 바인딩.
- `CameraSelector.DEFAULT_BACK_CAMERA` ↔ `CameraSelector.DEFAULT_FRONT_CAMERA` 를 버튼 한 번으로 전환
  (`CameraController.switchCamera()`).
- 요청한 렌즈가 없는 기기에서는 자동으로 안전한 쪽으로 되돌아갑니다.
- 화질: FHD 우선, 미지원 시 `FallbackStrategy.higherQualityOrLowerThan(SD)` 로 자동 폴백.

### 1.2 블랙박스 루프 녹화
- 녹화를 **구간(1분 / 3분 / 5분)** 으로 쪼개서 저장하고, 구간이 끝나면 자동으로 다음 구간을 이어서 녹화합니다.
  (`RecordingController` 의 `rotateRunnable` → `VideoRecordEvent.Finalize` → 다음 구간 시작)
- **저장 용량 한도(1GB / 2GB / 5GB)** 를 넘으면 가장 오래된 *일반* 영상을 자동 삭제합니다.
  (`ClipStorage.enforceLimitBlocking()`)
- 저장 위치: **`Movies/PickleBlackbox`** (MediaStore). 갤러리·파일 탐색기에서 바로 재생됩니다.
  - Android 10(API 29)+ : `MediaStore.Video` + `RELATIVE_PATH` + `IS_PENDING` 사용
  - Android 9(API 28)- : 공용 `Movies/PickleBlackbox` 폴더 + `MediaScanner`
- 촬영 중에는 임시 파일(`Android/data/<패키지>/files/segments`)에 쓰고, 구간이 끝나면 MediaStore로 게시한 뒤
  임시 파일을 지웁니다. 앱이 비정상 종료돼 남은 임시 파일은 다음 실행 때 정리합니다.

### 1.3 충격 감지(G-센서) + 수동 긴급 잠금
- `SensorManager` 가속도 센서로 합성 가속도를 계산해 임계값(기본 2.6G)을 넘으면 충격으로 판단
  (`ImpactDetector`, 2초 쿨다운).
- 충격이 감지되면 **그 순간 녹화 중인 구간이 잠금 상태로 저장**됩니다.
- 수동 잠금 버튼: 녹화 중이면 현재 구간, 녹화 중이 아니면 가장 최근 영상을 잠급니다.
- 잠금 영상은 파일명 앞에 **`LOCKED_`** 가 붙고, 용량 정리에서 **절대 삭제되지 않습니다.**
- 갤러리에서 잠금/해제를 다시 바꿀 수 있습니다.

### 1.4 화면 HUD
- 실시간 녹화 타이머(HH:MM:SS), 다음 구간 전환까지 남은 시간
- 현재 날짜·시간(초 단위)
- 렌즈 배지: `전면 카메라` / `후면 카메라`
- GPS 속도(km/h) — 위치 권한이 없으면 `GPS 대기` 로 표시되고 앱은 정상 동작
- 저장 사용량 / 한도 (`저장 1.24GB / 2GB`)
- 잠금 상태 배지, 녹화 상태 표시(REC 점멸 색)
- 녹화 중에는 화면이 꺼지지 않습니다(`keepScreenOn`).

### 1.5 녹화 목록 / 재생
- `GalleryActivity` + `RecyclerView` 로 저장된 클립 목록(썸네일·시간·길이·용량·잠금 배지).
- 항목을 누르면 `VideoView` + `MediaController` 로 바로 재생.
- 잠금 토글, 삭제(확인 다이얼로그) 지원.

### 1.6 AdMob 수익화
- 앱 ID: `AndroidManifest.xml` 의 `com.google.android.gms.ads.APPLICATION_ID` (테스트 ID)
- 배너: **메인 화면 하단**, **갤러리 화면 하단** (앵커드 적응형 배너)
- 전면광고: **녹화 정지 시**, **설정/갤러리 열기 전** — 3분 쿨다운
  (`AdConfig.INTERSTITIAL_COOLDOWN_MS`)
- 실제 ID로 바꾸는 위치는 **3장**에 정리되어 있고, 코드에는 `TODO(AdMob)` 주석이 달려 있습니다.

### 1.7 GitHub Actions 자동 APK 빌드
- `.github/workflows/build-apk.yml`
- `actions/checkout@v4` → `actions/setup-java@v4`(JDK 17) → `gradle/actions/setup-gradle@v4`(Gradle 8.9)
  → wrapper 자동 생성(없을 때만) → `./gradlew assembleDebug` → `actions/upload-artifact@v4`
- `main` 에 푸시하거나 Actions 탭에서 **Run workflow** 하면 `pickle-blackbox-debug-apk` 아티팩트로
  `app-debug.apk` 를 내려받을 수 있습니다.

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
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
> `local.properties` 는 `.gitignore` 에 포함되어 있습니다. 커밋하지 마세요.
> Gradle 래퍼(`gradlew`, `gradle/wrapper/gradle-wrapper.jar`)가 포함되어 있어 별도 Gradle 설치 없이 빌드됩니다.
> 래퍼가 없는 환경에서는 `gradle wrapper --gradle-version 8.9` 로 재생성하면 됩니다.

### 2.3 GitHub Actions 로 APK 받기
```bash
git push origin main
gh run list                     # 진행 상태 확인
gh run watch                    # 완료까지 대기
gh run download <run-id> -n pickle-blackbox-debug-apk
```

디버그 빌드는 패키지명이 `com.pickle.blackbox.debug` 라서 정식 빌드와 함께 설치할 수 있습니다.

---

## 3. ⚠️ AdMob 실제 ID로 교체하기 (출시 전 필수)

지금 들어 있는 값은 **구글 공식 테스트 ID** 입니다. 테스트 광고는 항상 채워지지만 **수익은 0원**입니다.
아래 3곳만 바꾸면 됩니다.

### 3.1 App ID → `AndroidManifest.xml`
`app/src/main/AndroidManifest.xml`
```xml
<!-- TODO(AdMob): replace with your real AdMob App ID -->
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-3940256099942544~3347511713" />
```
→ AdMob 콘솔 **앱 설정 > 앱 ID** 값(`ca-app-pub-################~##########`, `~` 구분자)으로 교체.
형식이 틀리면 앱이 시작하자마자 종료되니 복사/붙여넣기 하세요.

### 3.2 광고 단위 ID → `AdConfig.kt`
`app/src/main/java/com/pickle/blackbox/ads/AdConfig.kt`
```kotlin
// TODO(AdMob): replace with your real Banner ad unit ID.
const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

// TODO(AdMob): replace with your real Interstitial ad unit ID.
const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
```
- AdMob 콘솔 **앱 > 광고 단위** 에서 `배너`, `전면` 두 개를 만들고 각각의 ID(`~`가 아니라 `/` 구분자)를 넣습니다.
- 광고를 잠시 끄고 싶으면 같은 파일의 `ADS_ENABLED = false`.

### 3.3 테스트 기기 등록 (무효 트래픽 방지)
실제 ID로 바꾼 뒤 **본인 폰에서 광고를 반복 클릭하면 무효 트래픽으로 계정이 정지될 수 있습니다.**
출시 전에는 테스트 기기를 등록해 두세요.

```kotlin
// MainActivity.onCreate() 등에서 1회
MobileAds.setRequestConfiguration(
    RequestConfiguration.Builder()
        .setTestDeviceIds(listOf("YOUR_TEST_DEVICE_ID"))
        .build()
)
```
기기 ID는 실제 광고를 한 번 요청했을 때 Logcat 에
`Use RequestConfiguration.Builder().setTestDeviceIds(Arrays.asList("33BE2250B43518CCDA7DE426D04EE231"))` 처럼 찍힙니다.

### 3.4 app-ads.txt
1. `docs/app-ads.txt` 파일을 열어 `pub-0000000000000000` 을 **본인 게시자 ID** 로 바꿉니다.
2. 개발자 웹사이트 **루트**에 `https://내도메인.com/app-ads.txt` 로 업로드합니다.
   (Google Play 스토어 등록정보의 "개발자 웹사이트"와 **같은 도메인**이어야 합니다.)
3. AdMob 콘솔 **앱 > app-ads.txt** 에서 확인(반영까지 최대 24시간).

### 3.5 광고 배치 정책 체크리스트
- [x] 배너가 콘텐츠(프리뷰/HUD, 목록)를 가리지 않음
- [x] 전면광고가 녹화 버튼을 누르는 순간을 가로채지 않음(정지·설정 진입 시점만)
- [x] 전면광고는 3분 쿨다운으로 과다 노출 방지
- [x] 광고와 무관한 버튼 위에 겹쳐 배치하지 않음
- [ ] 출시 빌드에서 테스트 ID 제거 확인

---

## 4. Play 스토어 출시 단계

1. **서명 키 생성**
   ```bash
   keytool -genkeypair -v -keystore pickle-blackbox-upload.jks \
     -alias pickleblackbox -keyalg RSA -keysize 2048 -validity 10000
   ```
   `*.jks` 는 `.gitignore` 에 의해 커밋되지 않습니다. 키를 잃어버리면 업데이트가 불가능하니 안전하게 보관하세요.
2. **`app/build.gradle.kts` 의 release 서명 설정** (`TODO(PlayStore)` 부분)
   ```kotlin
   signingConfigs {
       create("release") {
           storeFile = file("${rootProject.projectDir}/keystore.properties" 의 경로)
           storePassword = ...
           keyAlias = ...
           keyPassword = ...
       }
   }
   buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
   ```
   비밀번호는 `keystore.properties`(gitignore 대상)에 두고 `Properties` 로 읽는 방식을 권장합니다.
3. `versionCode` / `versionName` 증가 (현재 1 / 1.0.0)
4. **AAB 빌드** → `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
5. **Play Console**
   - 앱 만들기 → 앱 콘텐츠 작성
   - **권한 선언**: 카메라·마이크·위치(선택) 사용 목적 설명
   - **광고 포함** 여부: 예 (AdMob)
   - **데이터 안전성**: 동영상·대략적 위치 수집/저장, 광고 ID 사용(AdMob SDK) 신고
   - **개인정보처리방침 URL** 필수 (광고 ID 수집 고지 포함)
   - **앱 액세스 권한**: 로그인 없이 사용 가능하므로 "모든 기능 사용 가능" 안내
6. 내부 테스트 → 비공개 테스트(12명·14일) → 프로덕션 출시
7. 출시 후 **app-ads.txt 확인** 및 AdMob 결제 정보 입력

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
│   ├── libs.versions.toml           # 버전 카탈로그 (모든 라이브러리 버전)
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
        │   ├── BlackboxApp.kt               # Application: 설정/저장소/광고 싱글턴
        │   ├── ads/
        │   │   ├── AdConfig.kt              # ★ 실제 광고 단위 ID 교체 위치
        │   │   └── AdManager.kt             # 배너 + 전면광고(쿨다운)
        │   ├── camera/
        │   │   ├── CameraController.kt      # CameraX 바인딩 / 전·후면 전환
        │   │   └── RecordingController.kt   # 구간 루프 녹화 / 잠금 처리
        │   ├── location/SpeedTracker.kt     # GPS 속도(권한 안전)
        │   ├── media/
        │   │   ├── Clip.kt                  # 클립 모델
        │   │   ├── ClipStorage.kt           # MediaStore 게시·조회·삭제·용량 정리
        │   │   └── VideoThumbs.kt           # 목록 썸네일
        │   ├── sensor/ImpactDetector.kt     # 가속도 센서 충격 감지
        │   ├── settings/AppSettings.kt      # 사용자 설정
        │   ├── ui/
        │   │   ├── MainActivity.kt          # HUD / 녹화 / 잠금 / 설정
        │   │   ├── GalleryActivity.kt       # 목록 · 재생 · 삭제
        │   │   └── ClipAdapter.kt
        │   └── util/Formatters.kt
        └── res/
            ├── layout/ (activity_main, activity_gallery, item_clip, dialog_settings, dialog_player)
            ├── drawable/ (아이콘 · HUD 배경)
            ├── values/ (colors, strings, themes)
            └── mipmap-anydpi-v26/ic_launcher.xml
```

---

## 7. 권한

| 권한 | 사용 목적 | 없을 때 |
| --- | --- | --- |
| `CAMERA` | 프리뷰·녹화 | 녹화 불가(안내 패널 표시) |
| `RECORD_AUDIO` | 소리 함께 녹음 | 무음으로 녹화 |
| `ACCESS_FINE_LOCATION` | HUD 속도 표시 | `GPS 대기` 표시, 앱은 정상 |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | 구형 기기에서 `Movies/PickleBlackbox` 쓰기 | 저장 실패 안내 |
| `INTERNET`, `ACCESS_NETWORK_STATE` | AdMob 광고 | 광고만 미표시 |

---

## 8. 설정 항목

| 항목 | 값 | 기본값 |
| --- | --- | --- |
| 녹화 구간 길이 | 1 / 3 / 5 분 | 3분 |
| 저장 용량 한도 | 1 / 2 / 5 GB | 2GB |
| 충격 감도 | 1(둔함, 3.4G) ~ 5(예민, 1.9G) | 3 (2.6G) |
| 충격 감지 시 자동 잠금 | on/off | on |
| 소리 함께 녹음 | on/off | on |
| GPS 속도 표시 | on/off | on |

설정 변경은 **다음 녹화 구간부터** 적용됩니다(현재 구간은 이어서 저장).

---

## 9. 알려진 제한과 다음 단계

- **백그라운드/화면 꺼짐 녹화 미지원**: CameraX는 Activity가 `STOP` 되면 use case를 언바인드합니다.
  상시 녹화(주차 녹화 등)가 필요하면 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` 포그라운드 서비스에서
  `bindToLifecycle` 대신 `LifecycleService` 의 생명주기에 바인딩하도록 확장해야 합니다.
  (`RecordingController` 는 화면과 분리되어 있어 서비스로 옮기기 쉽게 작성했습니다.)
- **녹화 중 전·후면 전환 불가**: 전환 시 use case를 다시 바인딩하므로 정지 후 전환하도록 안내합니다.
  (자동으로 정지→전환→재시작하려면 `RecordingController` 에 세션 재개 플래그를 추가)
- 갤러리·설정 화면으로 이동하면 녹화가 안전하게 종료·저장됩니다(위 서비스 확장 시 해결).
- 세로 고정(`screenOrientation="portrait"`) — 가로 거치대용으로 쓰려면 manifest의 두 Activity에서 제거하세요.
- 프론트 카메라가 FHD를 지원하지 않는 기기에서는 SD로 자동 폴백합니다.

---

## 10. 트러블슈팅

| 증상 | 확인 |
| --- | --- |
| 앱이 시작하자마자 종료 | `AndroidManifest.xml` 의 AdMob App ID 형식(`~` 포함) 확인 |
| `SDK location not found` | `local.properties` 에 `sdk.dir=...` 또는 `ANDROID_HOME` 설정 |
| 빌드는 되는데 설치 실패 | 디버그는 `com.pickle.blackbox.debug` 로 설치됩니다(서명 충돌 시 기존 앱 삭제) |
| 저장된 영상이 안 보임 | 권한 허용 여부, 갤러리 앱 새로고침. 파일은 `Movies/PickleBlackbox` |
| 광고가 안 뜸 | 인터넷 연결, 테스트 ID는 즉시 채워짐(빈 화면이면 SDK 초기화 로그 확인) |
| 충격 감지가 너무 예민/둔함 | 설정 → 충격 감도 (1~5) 조정 |
| Gradle 래퍼 오류 | `gradle wrapper --gradle-version 8.9` 로 재생성하거나 Actions의 setup-gradle 사용 |

---

## 11. 라이선스 / 고지

- 이 프로젝트는 사용자가 자유롭게 수정·배포할 수 있도록 작성된 샘플 앱입니다.
- AdMob, Google Play 서비스, CameraX, AndroidX, Material Components 는 각 제공자의 라이선스를 따릅니다.
- 실제 배포 시 개인정보처리방침에 **광고 ID 수집(AdMob)** 과 **영상·위치 데이터 처리** 를 명시해야 합니다.
