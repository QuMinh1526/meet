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
2. tạo thiết bị uinput với **đúng** các bit EV/KEY/ABS và absinfo của máy thật;
3. **verify** thiết bị ảo mà không cần `/proc/bus/input/devices`: (a) `UI_GET_SYSNAME` +
   `/sys/devices/virtual/input/<sysname>` tồn tại, (b) so sánh danh sách `/dev/input` trước/sau
   `UI_DEV_CREATE` (kèm inotify, poll tối đa 1 s), (c) mở node mới và `EVIOCGNAME` khớp tên ảo;
   một cách thành công là đủ — chỉ sau đó mới `EVIOCGRAB`, nếu không thì `UI_DEV_DESTROY`;
4. đọc bằng `poll()` timeout 100 ms, gom đến `SYN_REPORT` rồi `write()` một lần;
5. mỗi slot: `virt = raw` khi chạm xuống, `virt += (raw-last_raw)*k` khi di chuyển,
   clamp `[min,max]`, reset khi nhấc; mọi event khác (pressure, major, BTN_TOUCH, SYN) giữ nguyên;
6. `k` đọc bằng `std::atomic<float>` — đổi realtime, không khoá.

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
    SensiService.kt          # foreground service (specialUse) + heartbeat + watcher
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
**`release/SensiTune-1.0.0.apk`** — xem `release/README.md` để biết fingerprint và cách verify/install.

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
4. Kéo thanh X/Y (bước 0.05, 0.50–2.50). Bật **Khóa X = Y** nếu muốn hai hệ số bằng nhau.
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
- Không bắt buộc `INPUT_PROP_DIRECT`: thiếu cờ này chỉ làm thiết bị xếp hạng thấp hơn.
- Không cần đọc `/proc/bus/input/devices` (một số ROM Android 12 chặn shell đọc file này):
  verify dùng `UI_GET_SYSNAME`/sysfs, diff `/dev/input` + inotify, và `EVIOCGNAME` trên node mới.
- Kernel < 4.5 thiếu `UI_ABS_SETUP` → tự fallback API `uinput_user_dev` cũ.
- Cảm ứng có thể dùng protocol MT-A (không có `ABS_MT_SLOT`) → engine dùng slot 0 duy nhất,
  vẫn an toàn nhưng không scale đa ngón chính xác như MT-B.
- Watchdog, heartbeat, phím cứng và nút đỏ luôn có hiệu lực cao hơn logic theo app; nhả khẩn cấp
  sẽ tắt luôn chế độ theo app cho tới khi người dùng bật lại bằng tay.
- Ứng dụng không tự bật khi khởi động máy và không chạy nền nếu người dùng không chọn.
