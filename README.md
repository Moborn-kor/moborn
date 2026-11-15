# Moborn HUD

Rokid Glasses에 길안내 핵심 정보만 띄우는 HUD. 지도 화면을 미러링하지 않고
화살표, 남은 거리, 속도 같은 값만 글자와 선으로 보여 준다.

## 구성

| 모듈 | 설치 위치 | 역할 |
|---|---|---|
| `phone` | 휴대폰 | 안내 정보 수집(내비 알림, GPS, 티맵 경로) 후 블루투스로 전송 |
| `glasses` | 안경 | 받은 정보를 화면에 그림. 켜면 페어링된 휴대폰을 자동으로 찾음 |
| `shared` | - | 전송 형식, 알림 문구 해석, 경로 추적 (단위 테스트 포함) |

## 모드

- **자동차**: 티맵·네이버지도 등의 길안내 알림을 읽어 회전 방향, 거리, 남은 시간 등을 표시. 속도는 휴대폰 GPS.
- **도보**: 티맵 보행자 경로 API로 경로를 받아 자체 안내. SK open API에서 발급한 appKey 필요.
- **데모**: 안경 표시를 확인하기 위한 가짜 주행.

## 빌드

```
./gradlew :phone:assembleDebug :glasses:assembleDebug
./gradlew :shared:testDebugUnitTest
```

`local.properties`에 `sdk.dir`(Android SDK 경로)이 있어야 한다.

## 설치와 사용

1. `phone-debug.apk`를 휴대폰에 설치.
2. `glasses-debug.apk`를 Rokid-APKs(CXR-L 모드)로 안경에 설치.
3. 휴대폰 앱에서 권한 허용 → 모드 선택 → 시작.
4. 안경에서 Moborn HUD 실행. 자동으로 연결된다.
