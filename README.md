# HanPinIME - chinese input method editor by using hangul  
HanPinIME는 한글을 이용한 중국어 입력기입니다.  

이 앱은 중국인들이 중국어를 입력할때 알파벳을 쓰듯이 한국사람들이 중국어를 입력할때 한글을 써서 입력하게 해줍니다.  
윈도우의 한자키를 눌러서 한자를 조합해 입력하는 방식과 같다고 생각하시면 됩니다.  

## 주요 기능  
윈도우의 한자키를 눌러서 한자를 입력하는것은 단어만 가능하지만 문장의 중국어또한 입력이 가능케합니다.  
또한 이 앱은 인공지능이 중국어를 학습하여 한국식한자어뿐만아니라 중국어또한 그 한자의 한글독음을 치면 알맞게 후보로 추천해줍니다.  
입력할 후보한자중에서 정답일 것 같은 한자단어들을 파란색으로 추천해줍니다.  
입력할 후보한자중에서 쓰면 안될 것 같은 한자단어들을 빨간색으로 경고해줍니다.  

## 설치 방법  
### 개발자모드 들어가기  
개발자 모드는 [설정] > [휴대전화 정보] > [소프트웨어 정보]에서 '빌드 번호'를 7번 연속 터치하여 활성화합니다.  
이후 설정 메뉴 최하단에 [개발자 옵션]이 나타나며, 이후 들어가서 USB 디버깅을 허용해줍니다.  
그리고 휴대폰과 PC를 c타입 유선잭으로 잘 연결합니다.  

### 안드로이드 스튜디오 설치  
안드로이드 스튜디오 설치 후   
C:\Users\사용자명\AndroidStudioProjects 에 HanPin 폴더를 집어넣고  
안드로이드 스튜디오에서  
File -> Sync Project with Gradle Files  
-> Run 'app'  

## 프로젝트 구조  
```
HanPin/  
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
       ├─ java/com/hanpin/ime/  
       │   ├─ CandidateEngine.kt  
       │   ├─ HangulComposer.kt  
       │   ├─ HanPinIME.kt  
       │   ├─ Logger.kt  
       │   ├─ MainActivity.kt  
       │   ├─ ModelRunner.kt  
       │   ├─ NGram.kt  
       │   ├─ PinyinProcessor.kt  
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
한자는 알지만 중국어 알파벳(한어병음)은 잘 몰라서 중국어입력이 어려울때  
중국어에 능통하더라도 "상형문자"나 "뇌진탕"같은 어려운 용어는 중국어 알파벳으로 입력하기 어려울 때  
한글로 빠르게 중국어를 입력하고 싶을 때  
번역기사용으로 내가 원하는 단어가 아닌 중국어입력을 강제하는걸 원치 않을 때  
중국어를 한자어로써 이해하면서 중국어를 직접 입력해보며 학습하고싶을 때  
