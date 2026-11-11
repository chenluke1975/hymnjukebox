<p align="center"><img src="resources/icon-512.png" width="128" alt="詩歌點唱機 icon"></p>

# 詩歌點唱機 Hymn Jukebox

大按鈕、操作簡單的詩歌播放器。所有內容都來自**台灣福音書房**的官方網站，並從官網直接串流；本 App 不收錄任何音檔、歌詞或樂譜。
A big-button hymn player. Everything comes from, and is streamed directly from, the official websites of **Taiwan Gospel Book Room (TWGBR)**. The app bundles no audio, lyrics or scores.

> 本 App 為個人開發的開源軟體，與財團法人台灣福音書房無關，也不是其官方產品。詩歌的著作權屬於台灣福音書房。
> Independent open-source software, not affiliated with or endorsed by Taiwan Gospel Book Room, which owns the copyright of the hymns.

---

## 中文說明

### 功能
- **點歌（號碼盤）**：選「詩歌／補充本／兒童詩歌」，用大數字鍵輸入號碼，按「開啟這首」。
  App 會在內建瀏覽器打開福音書房官方 **影音美地**（mygoodland.video）「詩歌伴奏」中包含這一首的網頁，
  自動捲到並點選「第 N 首」，由官網自己的播放器播放。可按「上一首／下一首」換歌。
  **影音美地是福音書房的付費訂閱服務**，第一次請用自己的帳號登入；需有效訂閱才能播放。
- **試聽**：列出福音書房商店（twgbr.org.tw）各詩歌專輯公開的「線上試聽」，免費、免登入。
  按「播放」直接從官網串流（約 1～3 分鐘的精選片段），可「排隊」連續播放，背景與關螢幕也能播。
  按「曲目」看專輯歌單；標有詩歌號碼（如「詩75」「補461」）的曲目可直接跳到該首伴奏。
- **搜尋歌名**：搜尋專輯名稱、專輯曲目，以及官網有標示歌名的詩歌伴奏（補充本、兒童詩歌等）。
  第一次需按「從官網讀取歌名」（約 3 分鐘），之後只讀手機裡的暫存。
- **最愛**與**最近點過**。
- 播放列：上一首／播放‧暫停／下一首／停止／清單；通知列與耳機按鍵也能控制。
- 大字、大按鈕，適合長輩使用。

### 內容來源（執行時讀取，不內建）
| 用途 | 官方網址 |
|---|---|
| 詩歌伴奏索引 | https://www.mygoodland.video/teachers-detail/33/ |
| 各冊伴奏頁（例：詩歌第1至100首） | https://www.mygoodland.video/course-detail/155/ |
| 專輯列表（中英文詩歌、讚美詩集、兒童影音、英文詩歌） | https://www.twgbr.org.tw/categories/… |
| 專輯「線上試聽」音檔 | https://cd.twgbr.org/… 、 https://play.twgbr.org/… |

App 只會連到 `twgbr.org.tw`、`twgbr.org`、`mygoodland.video` 這些官方網域；只在手機暫存「網頁連結與標題」，避免每次開啟都重新讀取官網，不會下載或保存音檔。

### 權限
- `INTERNET`：從官網串流。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`：關螢幕或切到背景時繼續播放試聽。
- `POST_NOTIFICATIONS`：在通知列顯示播放控制（Android 13 以上會詢問一次）。

沒有廣告、不追蹤、不蒐集任何資料。

### 限制
- 依號碼播放「整首伴奏」需要影音美地訂閱；沒有訂閱只能用「試聽」（專輯精選片段）。
- 影音美地網頁是在 App 內的瀏覽器播放；離開該畫面後是否繼續播放取決於官網播放器。
- 「詩歌」本（1–780 首）的影音美地頁面只標「第 N 首」沒有歌名，所以這部分只能用號碼找。
- 少數專輯官網沒有試聽檔，會顯示「沒有試聽」。
- 官網改版時可能需要更新 App。
- 若想免費依號碼聽整本詩歌伴奏，可以使用福音書房官方的 App。

### 建置
```
./gradlew assembleRelease     # 未簽名版（F-Droid 可自行簽名）
./gradlew testReleaseUnitTest # 單元測試
LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveSiteTest*'  # 實際連線官網測試
```
只使用 Android 平台 API（Kotlin），沒有 AndroidX、沒有 Google Play 服務、沒有第三方函式庫。

---

## English

### Features
- **Number pad**: choose Hymns / Supplement / Children, type the number with big keys and tap *Open hymn*.
  The official **mygoodland.video** "hymn accompaniment" page that holds that hymn opens in an in-app browser,
  scrolled to and tapping "No. N", so the site's own player plays it. *Previous / Next* step through hymns.
  **mygoodland.video is TWGBR's paid subscription service**: sign in with your own account; an active subscription is needed to play.
- **Previews**: TWGBR's free public "online preview" of each hymn album on twgbr.org.tw, streamed directly from the official server
  (1–3 minute excerpts). Queue several albums; playback continues in the background with the screen off.
  *Tracks* shows the album's track list; tracks marked with a hymn number jump to that accompaniment.
- **Title search** across album names, album tracks and the hymn titles shown on the official accompaniment pages
  (first read the titles once from the official sites, ~3 minutes).
- **Favorites** and **Recent**.
- Player bar: previous / play-pause / next / stop / queue, plus notification and headset controls.
- Large text and big buttons.

### Permissions
`INTERNET`; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` for background playback;
`POST_NOTIFICATIONS` for the playback notification. No ads, no tracking, no data collection.

### Limitations
- Full accompaniments by number require a mygoodland.video subscription; without one only album previews play.
- The main *Hymns* book pages on mygoodland.video show only "No. N" (no titles), so those are found by number.
- Some albums have no preview on the official site.
- Changes to the official sites may require an app update.

## License
Code: MIT (see [LICENSE](LICENSE)). The hymns, recordings, titles and site content belong to Taiwan Gospel Book Room and are **not** covered by this license.
