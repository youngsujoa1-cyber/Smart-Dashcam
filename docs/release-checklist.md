# 출시 체크리스트 (Play 스토어 + AdMob)

## A. AdMob
- [ ] AdMob 앱 생성, **App ID** 발급
- [ ] `AndroidManifest.xml` 의 `com.google.android.gms.ads.APPLICATION_ID` 를 실제 값으로 교체
- [ ] `AdConfig.kt` 의 `BANNER_UNIT_ID`, `INTERSTITIAL_UNIT_ID` 를 실제 광고 단위 ID로 교체
- [ ] 테스트 기기 ID 등록 (`MobileAds.setRequestConfiguration(...setTestDeviceIds...)`)
- [ ] `docs/app-ads.txt` 의 `pub-...` 를 본인 게시자 ID로 바꿔 개발자 웹사이트 루트에 업로드
- [ ] AdMob 콘솔에서 app-ads.txt 확인 완료
- [ ] 결제/세금 정보, 주소 인증 완료 (광고 수익 지급 조건)

## B. 서명 & 빌드
- [ ] 업로드 키 생성 (`keytool -genkeypair ...`) 후 안전한 곳에 백업
- [ ] `keystore.properties` 작성 (git 에 커밋 금지)
- [ ] `app/build.gradle.kts` 의 `TODO(PlayStore)` 서명 설정 완료
- [ ] `versionCode` / `versionName` 증가
- [ ] `./gradlew bundleRelease` → `app-release.aab` 생성 확인
- [ ] 릴리스 빌드에서 광고가 정상 노출되는지 실제 기기 확인

## C. 스토어 등록정보
- [ ] 앱 이름, 짧은 설명(80자), 자세한 설명
- [ ] 앱 아이콘 512×512 PNG, 그래픽 이미지 1024×500
- [ ] 스크린샷(폰 2~8장) — HUD, 갤러리 화면
- [ ] 카테고리: 자동차 / 도구, 태그 선택

## D. 앱 콘텐츠 (정책)
- [ ] 개인정보처리방침 URL (영상·위치·광고 ID 처리 명시)
- [ ] **광고 포함: 예**
- [ ] 데이터 안전성: 동영상, 대략적 위치, 광고 ID, 기기 ID 신고
- [ ] 권한 사용 사유: 카메라(녹화), 마이크(소리), 위치(속도 표시)
- [ ] 앱 액세스 권한: 로그인 없이 전체 기능 사용 가능
- [ ] 콘텐츠 등급 설문 작성
- [ ] 뉴스 앱 여부 / 타깃 연령(만 18세 이상 권장: 운전 중 사용 주의)

## E. 테스트 트랙
- [ ] 내부 테스트(최대 100명)로 설치·녹화·저장·광고 확인
- [ ] 비공개 테스트 12명 · 14일 (신규 개인 계정 요건)
- [ ] 실제 기기 2종 이상에서 전/후면 전환, 충격 잠금, 용량 정리 확인
- [ ] (선택) 프로덕션 출시

## F. 출시 후
- [ ] AdMob 광고 노출/수익 지표 확인
- [ ] 크래시(Android vitals) 모니터링
- [ ] Play 정책 업데이트 확인 (매년 targetSdk 요건 상향)

## G. 백그라운드 녹화 · 권한 (spyblackbox 1.1.0)
- [ ] `RecordingService` 가 `foregroundServiceType="camera|microphone|location"` 으로 선언되어 있는지 확인
- [ ] Play Console **포그라운드 서비스** 선언에 녹화 목적 기재 (Android 14+ 정책)
- [ ] 알림 권한(`POST_NOTIFICATIONS`) 요청 문구에 녹화 중 알림 목적 설명
- [ ] 홈 버튼·다른 앱 전환·화면 꺼짐 상태에서 녹화가 계속되는지 실제 기기 확인
- [ ] 녹화 중 전면/후면 전환 시 앞 구간이 저장되고 새 구간이 이어지는지 확인
- [ ] 세로/가로 회전 시 미리보기와 새 구간 방향 확인
- [ ] 설정 → 앱 언어에서 5개 언어 전환 후 화면·토스트·상시 알림 문구 확인
