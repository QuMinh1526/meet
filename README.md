# SensiTune

Ứng dụng Android nhân hệ số độ nhạy cho **toàn bộ quãng di chuyển ngón tay trên màn hình cảm ứng**,
chạy qua **Shizuku** (UserService uid 2000) và engine native C++/NDK.

Chỉ có 3 thứ để chỉnh: **bật/tắt**, **hệ số X**, **hệ số Y**.
**Không** macro, **không** drag/aim, **không** tự động hoá, **không** chia vùng màn hình.

- package: `com.example.sensitune`
- minSdk 26, targetSdk/compileSdk 34
- ABI: `arm64-v8a` (chính) + `armeabi-v7a`
- Thư viện ngoài duy nhất: `dev.rikka.shizuku:api` + `:provider` (13.1.5)

---

## 1. Kiến trúc & luồng dữ liệu

```
┌──────────────────────────┐        Shizuku bind (AIDL)        ┌───────────────────────────────┐
│ App process              │ ────────────────────────────────► │ shell process (uid 2000)      │
│ MainActivity             │                                   │ SensiUserService : Stub       │
│ SensiService (FGS)       │ ◄──────── getStatus/getLogs ───── │   │ System.load(lib)          │
│ ShizukuHelper / Prefs    │                                   │   ▼ JNI                       │
└──────────────────────────┘                                   │ libsensi_engine.so            │
                                                               │   real touchscreen (grab)     │
                                                               │        │ scale X/Y per slot   │
                                                               │        ▼                      │
                                                               │   /dev/uinput virtual touch   │
                                                               └───────────────────────────────┘
```

Native engine, mỗi event:

1. quét `/dev/input/event*` bằng `EVIOCGBIT`, chấp nhận thiết bị có `EV_ABS` +
   `ABS_MT_POSITION_X/Y` với `max > min` (không loại vì `resolution=0` hay trục phụ `max=0`);
   `INPUT_PROP_DIRECT` chỉ dùng để xếp hạng (ưu tiên DIRECT, sau đó diện tích X*Y lớn nhất);
   log từng node: tên, EV bits, mọi trục ABS (min/max/res/fuzz/flat), cờ DIRECT, errno khi lỗi;
2. tạo thiết bị uinput với **đúng** các bit EV/KEY/ABS và absinfo của máy thật, kèm
   `UI_SET_PROPBIT(INPUT_PROP_DIRECT)` — thiếu property này Android coi thiết bị là pointer/
   touchpad và biến cảm ứng thành con trỏ chuột; set lỗi thì huỷ, **không grab**;
3. **verify** thiết bị ảo mà không cần `/proc/bus/input/devices`: (a) `UI_GET_SYSNAME` +
   `/sys/devices/virtual/input/<sysname>` tồn tại, (b) so sánh danh sách `/dev/input` trước/sau
   `UI_DEV_CREATE` (kèm inotify, poll tối đa 1 s), (c) mở node mới và `EVIOCGNAME` khớp tên ảo;
   một cách thành công là đủ — chỉ sau đó mới `EVIOCGRAB`, nếu không thì `UI_DEV_DESTROY`;
4. đọc bằng `poll()` timeout 100 ms, gom đến `SYN_REPORT` rồi `write()` một lần;
5. mỗi slot: `virt = raw` khi chạm xuống, `virt += (raw-last_raw)*k` khi di chuyển, clamp `[min,max]`
   (full range, raw ở mép thì virt cũng ở mép), reset khi nhấc; mọi event khác (pressure, major,
   BTN_TOUCH, SYN) giữ nguyên;
6. hệ số được **chốt cho từng ngón** khi slot nhận `TRACKING_ID` mới. Rotation 0/2 (dọc):
   `kRawX=factorX, kRawY=factorY`; rotation 1/3 (ngang): `kRawX=factorY, kRawY=factorX`.
   Xoay giữa lúc đang chạm thì ngón cũ giữ cấu hình đã chốt, ngón mới dùng cấu hình mới;
7. **soft recenter**: offset `virt-raw` được theo dõi riêng từng trục thô. Vùng đệm
   `M = edgeMargin% × chiều dài trục đó` (X thô ngắn hơn Y thô nên không dùng chung ngưỡng px).
   Khi `virt` vào vùng đệm và offset đang đẩy về mép, offset giảm tuyến tính về 0 (sát mép
   `virt = raw`); ra xa mép offset tích luỹ lại. Không nhảy vọt, mỗi frame `virt` liên tục.
   Tuỳ chọn **reanchor** (mặc định tắt, tối đa 1 lần/giây/ngón) gửi `TRACKING_ID=-1` rồi chạm
   lại ở giữa — game có thể coi đó là lần chạm mới;
8. hệ số đọc bằng `std::atomic<float>` — đổi realtime, không khoá. `setScalingSuspended(true)`
   chuyển tiếp nguyên bản (k=1.0, tắt soft recenter) nhưng **vẫn grab**; tự hết hạn sau 10 giây
   nếu menu không gia hạn, và tắt khi heartbeat timeout.

### Cây thư mục

```
settings.gradle.kts
build.gradle.kts
gradle.properties
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/aidl/com/example/sensitune/ISensiService.aidl
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/sensi_engine.h
app/src/main/cpp/sensi_engine.cpp
app/src/main/java/com/example/sensitune/
    MainActivity.kt          # màn hình chính (bật/tắt, X/Y, thử, nút đỏ, chẩn đoán)
    DebugLogActivity.kt      # log realtime + bảng trạng thái + copy/share/lưu/xoá
    AppPickerActivity.kt     # chọn app mục tiêu + hệ số riêng
    SensiUserService.kt      # UserService chạy trong tiến trình shell
    NativeBridge.kt          # khai báo JNI + bảng mã lỗi tiếng Việt
    ShizukuHelper.kt         # Shizuku permission/bind + gọi AIDL an toàn
    SensiService.kt          # foreground service (specialUse) + heartbeat + watcher + overlay host
    PerfHud.kt               # HUD overlay: khóa/kéo thả, màu ngưỡng, xoay màn hình
    FloatMenu.kt             # nút tròn + bảng 2 tab Độ nhạy/HUD
    PerfSampler.kt           # đọc thermal/CPU/FPS trong tiến trình shell
    OverlayUi.kt             # quyền overlay, kích thước màn hình, view phụ trợ
    ui/kit/                  # bộ widget dùng chung (Context thường, không AppCompat/Compose)
        Kit.kt               # dp/sp, màu theo theme sáng/tối, KitReleasable
        LabeledSlider.kt     # label + giá trị + SeekBar (int<->float, bước)
        ToggleRow.kt         # label + Switch, cao >= 40dp
        SectionCard.kt       # khung nhóm bo góc + tiêu đề
        IconButton.kt        # nút biểu tượng >= 40dp
        TabBar.kt            # thanh tab chia đều bề ngang
        ColorDot.kt          # chấm trạng thái 8dp
        Stepper.kt           # label + giá trị + −/+
    ForegroundWatcher.kt     # UsageStats 500 ms, debounce 300 ms
    ReleaseReceiver.kt       # nút "Nhả cảm ứng" trên notification
    SensiTileService.kt      # Quick Settings tile
    Prefs.kt                 # SharedPreferences
    LogBuffer.kt             # ring buffer 2000 dòng phía Kotlin
    Actions.kt               # action constants
app/src/main/res/
    layout/activity_main.xml, activity_debug_log.xml, activity_app_picker.xml, item_app.xml
    values/strings.xml, themes.xml
    drawable/ic_launcher.xml, ic_notification.xml, ic_tile.xml
```

### Bảng trạng thái engine

| Trạng thái | Ý nghĩa | Chuyển sang |
|---|---|---|
| `IDLE` | chưa giữ cảm ứng, cảm ứng chạy bình thường | `READY` khi bấm bật và đã tạo xong thiết bị ảo |
| `READY` | đã tạo + verify thiết bị ảo, **chưa grab** | `ACTIVE` khi `EVIOCGRAB` thành công; `ERROR` nếu grab lỗi |
| `ACTIVE` | đang grab và scale | `IDLE` khi tắt / hết heartbeat / watchdog / phím cứng / nút đỏ; `ERROR` khi watchdog phát hiện loop treo |
| `ERROR` | lần bật trước thất bại hoặc bị watchdog can thiệp | `READY` ở lần bật kế tiếp |

Mọi đường thoát (return sớm, lỗi tạo uinput, lỗi grab, exception, signal) đều ungrab trước.

---

## 2. Build

Yêu cầu: Android Studio mới (AGP 8.5+), JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1.

```bash
# Mở thư mục này bằng Android Studio rồi Run, hoặc:
./gradlew assembleDebug        # Gradle wrapper 8.7
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

APK release đã ký (zipalign + Signature Scheme v2/v3):
**`release/SensiTune-1.0.3.apk`** — xem `release/README.md` để biết fingerprint và cách verify/install.

### Test host cho phần dò ABS và JSON log

Hai helper thuần (không phụ thuộc Android) nằm trong `app/src/main/cpp/sensi_abs.h` và
`sensi_json.h`; test dùng đúng dữ liệu `/dev/input/event1` của OPPO CPH2113
(bitmap ABS rỗng nhưng `EVIOCGABS` vẫn trả range thật):

```bash
scripts/run-native-tests.sh
# native host tests: OK (event1 fallback accepted, JSON intact)
```

### Native library phải được giải nén ra đĩa

UserService chạy trong tiến trình shell và load bằng
`System.load(<nativeLibraryDir>/libsensi_engine.so)`, nên lib **không được** để dạng
uncompressed-load-from-APK. Project đã cấu hình:

```kotlin
// app/build.gradle.kts
packaging { jniLibs { useLegacyPackaging = true } }
```
```xml
<!-- AndroidManifest.xml -->
<application android:extractNativeLibs="true" ...>
```

AGP sẽ cảnh báo vì `extractNativeLibs` nên đặt qua build file, nhưng cả hai đều được giữ để
chắc chắn APK có cờ `extractNativeLibs=true` và lib được cài vào `nativeLibraryDir`.
Trước khi `System.load`, UserService log đầy đủ `nativeLibraryDir`, `File.exists()`,
`File.canRead()`, kích thước file và (nếu thiếu) danh sách file trong thư mục.

Khi cập nhật app, nhớ tăng `versionCode` trong `app/build.gradle.kts`: Shizuku dùng nó để
quyết định tạo lại UserService (tránh trùng phiên bản cũ).

Cài:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Cài Shizuku và cấp quyền

1. Cài **Shizuku** (CH Play / GitHub `RikkaApps/Shizuku`).
2. Khởi động Shizuku qua ADB (không cần root):
   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```
   (hoặc theo hướng dẫn trong app Shizuku nếu máy đã root).
3. Mở SensiTune → bấm **“Kết nối lại Shizuku”** → chấp nhận hộp thoại cấp quyền.
4. Trong **Nhật ký**, kiểm tra `UID UserService: 2000`. Nếu không phải 2000, đừng bật engine.

## 4. Cách dùng

1. **Chẩn đoán** trước — không grab gì cả. Xem `/dev/uinput` có mở được không, thiết bị cảm ứng
   nào được chọn, SELinux mode. Nếu `uinputOpenable=false` → máy không hỗ trợ, **dừng ở đây**.
2. **Thử 10 giây** — grab đúng 10 giây rồi tự nhả. Kiểm tra cảm ứng vẫn đúng.
3. Bật **Bật SensiTune** với hệ số 1.00 → phải giống hệt bình thường.
4. Kéo thanh X/Y (bước 0.05, 0.50–10.00 trên màn hình chính, 0.50–2.50 trong menu nổi).
   X = chuyển động **ngang**, Y = chuyển động **dọc** của màn hình hiện tại, tự đổi khi xoay máy.
   Bật **Khóa X = Y** nếu muốn hai hệ số bằng nhau.
5. Rời app: mặc định engine **tự nhả**. Muốn chạy tiếp khi ở app khác, bật **Chạy nền**
   (foreground service + notification có nút nhả).
6. **Chỉ áp dụng trong app đã chọn**: cấp quyền *Usage Access*, chọn app, bật công tắc.
   Vào app mục tiêu ổn định ≥300 ms → grab; rời app → nhả hoàn toàn về IDLE.
   Mỗi app có thể có hệ số X/Y riêng.

## 5. Cơ chế an toàn

- **Watchdog thread** riêng: loop không báo nhịp >1 s → tự `EVIOCGRAB=0` + `UI_DEV_DESTROY`.
- **Heartbeat**: app/service gửi mỗi 1 s; quá **3 s** → engine tự nhả và về IDLE.
- **Phím cứng**: giữ **Vol+ và Vol- 2 giây** (đọc không grab) → nhả ngay.
- **Signal**: `SIGSEGV/SIGABRT/SIGBUS/SIGFPE` → handler async-signal-safe gọi `ioctl(EVIOCGRAB,0)`,
  đóng fd rồi `_exit`; kernel cũng tự nhả grab khi fd đóng.
- **Không bao giờ grab trước khi thiết bị ảo được tạo và verify**.
- **Không giữ lock khi syscall chặn**; mọi vòng lặp có `poll()` timeout và cờ `atomic`.
- UserService `destroy()` / binder chết → `nativeStop()` trong `try/finally`.
- Foreground service `onDestroy` → nhả cảm ứng.
- Nút đỏ **NHẢ CẢM ỨNG NGAY** + Quick Settings tile + action trên notification đều gọi `emergencyRelease()`.

## 6. Cứu máy (quan trọng)

Nếu cảm ứng bị “đơ” bất kể lý do gì, làm theo thứ tự:

1. **Giữ đồng thời Vol+ và Vol- trong 2 giây** — hoạt động ngay cả khi app treo.
2. Mở lại app → bấm nút đỏ **NHẢ CẢM ỨNG NGAY**.
3. Nhấn nút nguồn **tắt rồi bật màn hình**.
4. Dùng chuột/bàn phím USB (OTG) hoặc `scrcpy` để thao tác nếu cảm ứng vẫn chết.
5. Qua ADB:
   ```bash
   adb shell am force-stop com.example.sensitune
   adb shell pkill -f sensi
   ```
6. **Khởi động lại máy** — grab không lưu qua lần khởi động, cảm ứng chắc chắn trở lại.

Lưu ý: khi app hoặc UserService bị kill, kernel tự nhả `EVIOCGRAB`; cảm ứng trở lại bình thường
mà không cần làm gì.

## 7. Debug & log

- logcat tag `SensiEngine` (native) và `SensiTune` (Kotlin).
- Ring buffer native 2000 dòng (seq, timestamp ms, level, thread) đọc qua `getLogs(seq)`.
- Màn hình **Nhật ký** poll 500 ms: lọc mức, Copy, Chia sẻ, Lưu `.txt`, Xoá; bảng trạng thái gồm
  Shizuku/phiên bản, UID, thiết bị cảm ứng, trạng thái engine, hệ số, event/giây, độ trễ, heartbeat,
  app foreground hiện tại.
- Log bắt buộc có: mọi `errno` + `strerror`, danh sách/ứng viên thiết bị, từng bước khởi tạo,
  thời điểm grab/nhả kèm **lý do**, đổi hệ số, thống kê mỗi 5 giây (§ event đọc/ghi, EAGAIN,
  latency trung bình/max µs, gói drop).

## 8. Kiểm thử thủ công (theo thứ tự)

1. Chẩn đoán (không grab) — đọc kết luận.
2. Thử 10 giây — cảm ứng đúng, tự nhả sau 10 s.
3. Bật thật với hệ số 1.0 — không khác gì bình thường.
4. Đổi hệ số 1.5 — độ nhạy tăng ngay, log ghi thay đổi.
5. Kill app khi đang bật (`adb shell am force-stop com.example.sensitune`) — **cảm ứng trở lại trong ≤3 s**.
6. Kill Shizuku khi đang bật — cảm ứng trở lại, UI hiện nút kết nối lại.
7. Giữ Vol+ & Vol- 2 s — nhả ngay, log ghi “HARDWARE ESCAPE”.
8. Nút đỏ / tile / action notification — nhả ngay.
9. Xoay màn hình — hệ số và abs range không đổi.
10. Nhiều ngón (pinch) — từng slot scale độc lập, không nhảy.
11. Chế độ theo app: chuyển qua lại app mục tiêu ↔ app khác **20 lần liên tục**;
    kéo thông báo xuống khi đang trong game; khoá/mở màn hình; kill service khi đang trong game.
    Không được bật/tắt liên tục do SystemUI/launcher/IME.

## 9. Giới hạn & fallback an toàn

- Cần `/dev/uinput` mở được từ uid 2000. Một số ROM chặn bằng SELinux → app báo **không hỗ trợ**
  và **không grab** (thà không chạy còn hơn grab mà không chắc).
- Không tin `EVIOCGBIT(EV_ABS)`: một số ROM (OPPO CPH2113) trả bitmap rỗng dù thiết bị có đủ
  trục. Engine đọc bitmap vào buffer byte đã zero + log hex/errno, rồi **luôn** thử
  `EVIOCGABS` cho X/Y, slot, tracking id, pressure, touch/width major; range thật từ
  `EVIOCGABS` được dùng để tạo thiết bị ảo dù bitmap thiếu.
- Thiết bị nguồn không bắt buộc `INPUT_PROP_DIRECT` (chỉ dùng để xếp hạng), nhưng thiết bị ảo
  **bắt buộc** có property này để Android phân loại là touchscreen chứ không phải chuột.
- Không cần đọc `/proc/bus/input/devices` (một số ROM Android 12 chặn shell đọc file này):
  verify dùng `UI_GET_SYSNAME`/sysfs, diff `/dev/input` + inotify, và `EVIOCGNAME` trên node mới.
- Kernel < 4.5 thiếu `UI_ABS_SETUP` → tự fallback API `uinput_user_dev` cũ.
- Cảm ứng có thể dùng protocol MT-A (không có `ABS_MT_SLOT`) → engine dùng slot 0 duy nhất,
  vẫn an toàn nhưng không scale đa ngón chính xác như MT-B.
- Watchdog, heartbeat, phím cứng và nút đỏ luôn có hiệu lực cao hơn logic theo app; nhả khẩn cấp
  sẽ tắt luôn chế độ theo app cho tới khi người dùng bật lại bằng tay.
- Ứng dụng không tự bật khi khởi động máy và không chạy nền nếu người dùng không chọn.
- HUD/menu cần quyền **Hiển thị trên ứng dụng khác**; thiếu quyền thì chỉ ẩn overlay, engine vẫn nguyên.
- FPS lấy từ `dumpsys SurfaceFlinger --list | grep <package>` rồi `--latency <layer>`: bỏ dòng
  refresh period, lấy hiệu `ActualPresentTime` (cột 2, ns) giữa các dòng. Layer không còn → tự
  `--list` lại; không tìm thấy layer → hiện `--`, không báo lỗi.
- Kẹt mép khi hệ số > 1.0 được xử lý bằng **soft recenter**: vùng đệm `edgeMargin` (0–25%,
  mặc định 12%) tính riêng theo chiều dài từng trục thô; trong vùng đệm, offset giảm tuyến tính
  về 0 nên `virt` luôn đuổi kịp `raw` và vuốt vòng qua mép không bị đứng yên. Clamp cuối cùng vẫn
  là `[min,max]`, raw ở mép thì virt ở mép. Tuỳ chọn **“Chạm lại ở giữa”** (mặc định tắt) nhấc và
  chạm lại tại tâm khi chạm mép, tối đa 1 lần/giây — game có thể coi đó là lần chạm mới.
- Toạ độ thô của panel cố định theo màn hình dọc, nên hệ số X/Y được hoán đổi theo
  `Display.rotation` (AIDL `setRotation`) và chốt cho từng ngón khi ngón đó bắt đầu chạm.
- Nhiệt độ lấy từ `/sys/class/thermal` (đọc một lần, cache đường dẫn); zone trả giá trị vô lý
  (ví dụ `-40000`) bị bỏ qua. GPU % bị chặn trên nhiều ROM nên không có ô này.

## 10. HUD hiệu năng & menu nổi

Bật **Menu nổi** / **Hiện HUD** ở màn hình chính (cần quyền Hiển thị trên ứng dụng khác).
Cả hai overlay do `SensiService` (FGS) host, dùng View thường — không Compose/Fragment.

- **Menu nổi**: nút tròn 48dp, kéo thả, tự dính mép và lưu vị trí. Chạm để mở bảng:
  - tab **Độ nhạy**: **slider** X/Y (bước 0.05, 0.50–2.50) hiển thị giá trị, công tắc Khóa X=Y,
    thanh `edgeMargin`, công tắc Soft recenter (mặc định bật) và Chạm lại ở giữa (mặc định tắt),
    chấm đỏ nhỏ báo lỗi gửi, nút đỏ **NHẢ**. Khi bảng mở, menu gọi
    `setScalingSuspended(true)` (gia hạn mỗi ~2 giây, engine tự hết hạn sau 10 giây) nên slider
    bám đúng ngón dù engine đang ACTIVE; đóng bảng gọi `false`. Giá trị slider được coalesce
    (chỉ giá trị mới nhất), debounce ~50 ms, gửi trên HandlerThread riêng, gửi giá trị cuối khi
    nhả ngón — không gọi Binder trên main thread;
  - tab **HUD**: bật/tắt HUD hiệu năng, bật/tắt **HUD ngày giờ riêng**, bật/tắt từng chỉ số +
    đổi thứ tự bằng ↑/↓, **cỡ chữ dùng chung cho cả 2 HUD**, độ mờ, kiểu hàng ngang/cột dọc,
    ngưỡng vàng/đỏ, khóa/mở khóa vị trí và 4 nút mũi tên 8px.
  Bảng tự đóng sau 5 giây không chạm.
- **HUD ngày giờ**: cửa sổ overlay riêng, định dạng `EEE MMM d HH:mm:ss` (ví dụ
  `Fri Jun 7 12:59:59`), cập nhật mỗi giây, không chung với ô nhiệt độ/CPU/GPU. Cùng cơ chế
  khóa/kéo/vị trí riêng và cùng cỡ chữ với HUD hiệu năng.
- **HUD**: mỗi chỉ số một ô (`FPS`, `CPU°C`, `GPU°C`, `Pin°C`, `RAM°C`, `Thân°C`, `Lưng°C`,
  `CPU%`, xung CPU). Nhiệt độ > ngưỡng vàng/đỏ đổi màu. Mặc định **khóa**: cờ
  `FLAG_NOT_TOUCHABLE` nên chạm xuyên qua game, không cản. Mở khóa → viền sáng + 🔓, kéo được,
  tự khóa sau 15 giây; chạm giữ 1 giây trên HUD để khóa lại. Vị trí lưu riêng cho dọc/ngang.
- **Lấy mẫu**: HandlerThread riêng, 1000 ms/lần, chỉ khi HUD bật + màn hình sáng + service đã bind.
  Không chồng request (lượt trước chưa xong thì bỏ lượt). Mỗi lần lấy mẫu log thời gian.
  Chỉ số nào tắt thì không yêu cầu shell đọc. Lỗi đọc chỉ ẩn ô đó và log một lần.
- **Độc lập engine**: overlay lỗi/bị gỡ không đụng tới grab, heartbeat hay logic nhả; heartbeat
  vẫn chạy trên handler riêng và phím cứng/nút đỏ vẫn nhả ngay.
- **Vòng đời**: mọi `addView/removeView` bọc `try/catch` + cờ `attached`; `onDestroy`,
  `onTaskRemoved`, mất quyền overlay đều gỡ overlay và dừng sampler; xoay màn hình tính lại vị trí.
