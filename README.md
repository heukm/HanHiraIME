# HanHiraIME - japaneseIME for Korean
HanHiraIME는 한글을 이용한 일본어 입력기입니다. 

## 주요 기능  
기존의 일본어를 입력하는 것은 단어만 가능하지만 문장의 일본어또한 입력이 가능케합니다.  
또한 이 앱은 인공지능이 일본어를 학습하여 한자어뿐만아니라 히라가나와 가타카나의 한글음을 치면 알맞게 후보로 추천해줍니다.  

## 설치 방법  
### 개발자모드 들어가기  
개발자 모드는 [설정] > [휴대전화 정보] > [소프트웨어 정보]에서 '빌드 번호'를 7번 연속 터치하여 활성화합니다.  
이후 설정 메뉴 최하단에 [개발자 옵션]이 나타나며, 이후 들어가서 USB 디버깅을 허용해줍니다.  
그리고 휴대폰과 PC를 c타입 유선잭으로 잘 연결합니다.  

### 안드로이드 스튜디오 설치  
안드로이드 스튜디오 설치 후   
C:\Users\사용자명\AndroidStudioProjects 에 HanHira 폴더를 집어넣고  
안드로이드 스튜디오에서  
File -> Sync Project with Gradle Files  
-> Run 'app'  

## 프로젝트 구조  
```
HanHira/  
 ├─ build.gradle.kts(용량 1KB짜리)  
 ├─ settings.gradle.kts  
 ├─ gradle.properties  
 ├─ gradlew, gradlew.bat  
 ├─ gradle/wrapper/gradle-wrapper.properties  
 └─ app/  
    ├─ build.gradle.kts(용량 2KB짜리)  
    ├─ proguard-rules.pro  
    └─ src/main/  
       ├─ AndroidManifest.xml  
       ├─ java/com/hanhira/ime/  
       │   ├─ CandidateEngine.kt  
       │   ├─ HangulComposer.kt  
       │   ├─ HanHiraIME.kt  
       │   ├─ Logger.kt  
       │   ├─ MainActivity.kt  
       │   ├─ ModelRunner.kt  
       │   └─ TorchPredictor.kt  
       └─ res/  
          ├─ layout/  
          │   ├─ activity_main.xml  
          │   └─ candidate_item.xml  
          ├─ xml/  
          │   ├─ method.xml  
          │   ├─ english_keyboard.xml  
          │   └─ korean_keyboard.xml  
          ├─ drawable/  
          │   ├─ transparent.xml  
          │   └─ hanpin_logo.jpg  
          ├─ mipmap-anydpi-v26/  
          │   ├─ ic_launcher.xml  
          │   └─ ic_launcher_round.xml   
          └─ values/  
             ├─ strings.xml  
             └─ themes.xml
```

## 활용 예시  
한자는 알지만 일본어음은 잘 몰라서 일본어입력이 어려울때
일본의 한자는 너무 많은 음이 존재해서 제대로 음을 쳐도 그 한자가 뜨지않을 때
일본어에 능통하더라도 "상형문자"나 "귀납법", "연역법" 같은 어려운 용어는 일본어 한자로 입력하기 어려울 때  
한글로 빠르게 일본어를 입력하고 싶을 때  
번역기사용으로 내가 원하는 단어가 아닌 일본어 단어입력을 강제되는걸 원치 않을 때  
히라가나, 가타카나를 한글로 외우면서 직접 입력해보며 학습하고싶을 때
