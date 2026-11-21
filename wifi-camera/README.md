# SolveCam — 와이파이 DIY 카메라용 찍으면 풀이

책상 위에 단 ESP32 카메라가 문제를 보고, 같은 와이파이의 휴대폰·PC 브라우저에서 답을 보여 주고 읽어 줘요.

- 설치 페이지: https://jws760127-max.github.io/Solver/ (PC 크롬에서 버튼 한 번으로 카메라에 설치)
- 지원 보드: AI-Thinker ESP32-CAM, XIAO ESP32S3 Sense

## 구조
- `firmware/SolveCam` — 카메라 펌웨어 (와이파이 설정 화면, 사진 `/capture`, 풀이 화면 `/`, 기기 간 결과 공유)
- `web/index.html` — 카메라가 띄워 주는 풀이 화면. 고친 뒤 `python3 tools/make_page.py`로 `page.h`를 다시 만들어요
- `flasher` — 브라우저 설치 페이지 (ESP Web Tools)

GitHub Actions가 두 보드용 펌웨어를 빌드하고 설치 페이지를 GitHub Pages에 올려요.
