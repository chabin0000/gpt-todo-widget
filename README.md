# GPT 할 일 위젯

Android 4x2 다크/라이트 체크리스트 위젯입니다. 앱에서 본인의 ChatGPT Page 주소를 입력해 연결합니다.

시험판: Android ARM64에서 최초 로그인, 자동 가져오기 및 원본 완료 반영은 실기기 검증이 필요합니다. Android x86 에뮬레이터의 연결 런타임은 실행에 실패했습니다. 15분 주기 갱신은 운영체제 절전 정책에 따라 지연될 수 있습니다.

JDK 17, Android SDK 36, Gradle 8.11.1 및 AGP 8.10.1을 사용합니다. `node tools/fetch-runtime.cjs`로 공식 런타임을 준비한 뒤 Android Studio에서 빌드하세요. 런타임 바이너리와 서명키는 포함하지 않습니다.
