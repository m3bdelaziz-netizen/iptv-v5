@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.ExperimentalTvMaterial3Api
import java.util.Locale

object Lang {
    var ar by mutableStateOf(false)
    fun apply() {
        ar = when (Store.sp.getString("lang", "auto")) { "ar" -> true; "en" -> false; else -> Locale.getDefault().language == "ar" }
    }
}

private val AR = mapOf(
    "Home" to "الرئيسية", "Live TV" to "البث المباشر", "Guide" to "دليل البرامج", "Films" to "الأفلام",
    "Series" to "المسلسلات", "Radio" to "الراديو", "Search" to "بحث", "Settings" to "الإعدادات",
    "Loading your library..." to "جاري تحميل المكتبة...",
    "Recently added films" to "أحدث الأفلام", "Recently added series" to "أحدث المسلسلات", "Top rated films" to "الأفلام الأعلى تقييمًا",
    "▶  Play / Details" to "▶  تشغيل / تفاصيل", "▶  Play" to "▶  تشغيل", "All" to "الكل", "All channels" to "كل القنوات",
    "★ Favorites" to "★ المفضلة", "Hold OK = favorite" to "اضغط مطولًا على OK = مفضلة",
    "Nothing here (radio = categories with 'radio' in the name)" to "لا يوجد محتوى (الراديو = التصنيفات التي يحتوي اسمها على radio أو راديو)",
    "More sources (addons)" to "مصادر إضافية (الإضافات)",
    "Aspect" to "الأبعاد", "Audio" to "الصوت", "Subtitles" to "الترجمة", "★ Unfavorite" to "★ إزالة من المفضلة", "☆ Favorite" to "☆ إضافة للمفضلة",
    "Playlists" to "قوائم التشغيل", "Addons" to "الإضافات", "VPN profiles" to "ملفات VPN",
    "TMDB API key v3 (optional)" to "مفتاح TMDB API v3 (اختياري)", "Save TMDB key" to "حفظ مفتاح TMDB", "Saved" to "تم الحفظ",
    "Refresh library" to "تحديث المكتبة", "Clear favorites" to "مسح المفضلة",
    "Playlists (Xtream Codes)" to "قوائم التشغيل (Xtream Codes)", "Use" to "استخدام", "Delete" to "حذف",
    "Add playlist" to "إضافة قائمة", "Name" to "الاسم", "Server (http://host:port)" to "السيرفر (http://host:port)",
    "Username" to "اسم المستخدم", "Password" to "كلمة المرور", "Add & connect" to "إضافة واتصال", "Testing..." to "جاري الاختبار...",
    "Addons (extra film sources)" to "الإضافات (مصادر أفلام إضافية)", "Remove" to "إزالة",
    "Addon link (manifest.json or stremio://...)" to "رابط الإضافة (manifest.json أو stremio://...)",
    "Add from link" to "إضافة من رابط", "Import .json file" to "استيراد ملف .json", "Or paste JSON text" to "أو الصق نص JSON",
    "Add pasted JSON" to "إضافة JSON الملصق", "Added" to "تمت الإضافة", "Cannot read file" to "تعذر قراءة الملف",
    "Link addons use the Stremio addon format and need your TMDB key (set in Settings). Only direct http(s) stream links play; torrent-only streams are skipped." to
        "إضافات الروابط تستخدم صيغة Stremio وتحتاج مفتاح TMDB (من الإعدادات). تعمل فقط روابط البث المباشرة http(s) ويتم تجاهل التورنت.",
    "Search films, series, channels" to "ابحث في الأفلام والمسلسلات والقنوات", "Channels" to "القنوات",
    "Loading guide (can take up to a minute)..." to "جاري تحميل الدليل (قد يستغرق دقيقة)...",
    "Guide is not available from this provider" to "الدليل غير متاح من هذا المزود", "No guide data" to "لا توجد بيانات دليل",
    "VPN profiles (WireGuard .conf)" to "ملفات VPN (WireGuard .conf)", "Not connected" to "غير متصل", "Connect" to "اتصال",
    "Disconnect" to "قطع الاتصال", "Auto-connect: ON" to "اتصال تلقائي: مفعّل", "Auto-connect: off" to "اتصال تلقائي: متوقف",
    "Add profile" to "إضافة ملف", "Import .conf from file" to "استيراد .conf من ملف",
    "Direct link to .conf (optional)" to "رابط مباشر لملف .conf (اختياري)", "Import from link" to "استيراد من رابط",
    "Or paste: profile name" to "أو الصق: اسم الملف", "Paste full .conf text" to "الصق نص .conf كاملًا",
    "Add pasted profile" to "إضافة الملف الملصق", "VPN permission denied" to "تم رفض إذن VPN", "Download failed" to "فشل التنزيل",
    "Movies" to "الأفلام", "Live" to "مباشر", "Continue Watching" to "متابعة المشاهدة", "Categories" to "التصنيفات",
    "Nothing watched yet" to "لم تشاهد شيئًا بعد", "No favorites yet (hold OK on an item)" to "لا توجد مفضلة (اضغط مطولًا على OK)",
    "Nothing here" to "لا يوجد محتوى", "No playlist yet" to "لا توجد قائمة تشغيل", "Retry" to "إعادة المحاولة",
    "Press back again to exit" to "اضغط رجوع مرة أخرى للخروج", "Loading..." to "جاري التحميل...",
    "⏸  Pause" to "⏸  إيقاف مؤقت", "Fit" to "ملائم", "Fill" to "ملء", "Zoom" to "تكبير", "On" to "مفعّل", "Off" to "متوقف",
    "General" to "عام", "Playback" to "التشغيل", "Library" to "المكتبة", "Start screen" to "شاشة البداية",
    "Poster size" to "حجم الملصقات", "Small" to "صغير", "Medium" to "متوسط", "Large" to "كبير", "Huge" to "ضخم", "Normal" to "عادي",
    "Titles under posters" to "العناوين تحت الملصقات", "Show ratings" to "عرض التقييم", "Sort" to "الترتيب",
    "Provider order" to "ترتيب المزود", "Recently added" to "المضاف حديثًا", "A-Z" to "أبجدي", "Top rated" to "الأعلى تقييمًا",
    "Pure black background" to "خلفية سوداء", "Press Back twice to exit" to "اضغط رجوع مرتين للخروج",
    "Stream format" to "صيغة البث", "Buffer" to "التخزين المؤقت", "Fast start" to "بدء سريع", "Balanced" to "متوازن",
    "Default aspect" to "الأبعاد الافتراضية", "Resume where I stopped" to "استكمال من حيث توقفت",
    "Auto-play next episode" to "تشغيل الحلقة التالية تلقائيًا", "Seek step (Left / Right)" to "خطوة التقديم (يسار / يمين)",
    "Swap Up / Down channel keys" to "عكس مفاتيح تغيير القناة", "Show clock in player" to "إظهار الساعة في المشغل",
    "Audio language" to "لغة الصوت", "Subtitle language" to "لغة الترجمة", "Subtitle size" to "حجم الترجمة",
    "Arabic" to "العربية", "Auto" to "تلقائي", "Clear continue watching" to "مسح متابعة المشاهدة",
    "Appearance" to "المظهر", "Theme" to "السمة", "Midnight" to "منتصف الليل", "Graphite" to "رمادي غرافيت",
    "Super AMOLED" to "سوبر أمولد (أسود نقي)", "Accent color" to "لون التمييز",
    "Red" to "الأحمر", "Blue" to "الأزرق", "Green" to "الأخضر", "Purple" to "البنفسجي",
    "Orange" to "البرتقالي", "Teal" to "الفيروزي", "Pink" to "الوردي", "Gold" to "الذهبي",
    "★ Remove from favorites" to "★ إزالة من المفضلة", "☆ Add to favorites" to "☆ إضافة للمفضلة",
    "ⓘ Details" to "ⓘ التفاصيل", "✕ Remove from Continue Watching" to "✕ إزالة من متابعة المشاهدة",
    "Clear all Continue Watching" to "مسح كل متابعة المشاهدة", "Go to category" to "الذهاب إلى التصنيف",
    "Next episode" to "الحلقة التالية", "Parental guidance" to "التوجيه الأبوي", "Parental guidance (TMDB)" to "التوجيه الأبوي (TMDB)",
    "Violence" to "عنف", "Gore" to "دماء", "Blood" to "دم", "Nudity" to "عري", "Sexual content" to "محتوى جنسي",
    "Drugs" to "مخدرات", "Alcohol" to "كحول", "Strong language" to "ألفاظ نابية", "Torture" to "تعذيب",
    "Sexual violence" to "عنف جنسي", "Suicide" to "انتحار", "Smoking" to "تدخين",
    "Setup from phone (QR)" to "الإعداد من الهاتف (QR)", "Setup from your phone" to "الإعداد من هاتفك",
    "1. Connect your phone to the same Wi-Fi as this TV." to "١. صل هاتفك بنفس شبكة الواي فاي الخاصة بالتلفزيون.",
    "2. Scan the QR code with your phone camera." to "٢. امسح رمز QR بكاميرا هاتفك.",
    "3. Enter the Xtream server, username, password and/or TMDB key, then tap Save." to "٣. أدخل السيرفر واسم المستخدم وكلمة المرور و/أو مفتاح TMDB ثم اضغط حفظ.",
    "Works on your home network only. Keep this screen open until you save." to "يعمل على شبكتك المنزلية فقط. أبقِ هذه الشاشة مفتوحة حتى تحفظ.",
    "No Wi-Fi / network found" to "لا توجد شبكة واي فاي", "Preparing..." to "جاري التحضير...",
    "Last crash (send this text to get it fixed)" to "آخر تعطل (أرسل هذا النص لإصلاحه)", "Clear crash log" to "مسح سجل التعطل",
    "Choose categories" to "اختيار التصنيفات", "Channel numbers" to "ترقيم القنوات", "List position" to "حسب ترتيب القائمة",
    "Provider number" to "رقم المزود", "Same in every category" to "ثابت في كل التصنيفات",
    "Only the categories you tick are loaded. Press Save to apply." to "يتم تحميل التصنيفات التي تحددها فقط. اضغط حفظ للتطبيق.",
    "Select all" to "تحديد الكل", "Clear" to "إلغاء الكل", "Save" to "حفظ",
    "Load your playlist first, then come back here." to "حمّل القائمة أولاً ثم ارجع إلى هنا.",
    "Pick at least one category in " to "اختر تصنيفاً واحداً على الأقل في ",
    "ACTIVE" to "نشطة", "✓ In use" to "✓ قيد الاستخدام", "No playlists yet" to "لا توجد قوائم بعد",
    "Nothing saved yet" to "لا شيء محفوظ بعد", "Add one on the right, or set it up from your phone." to "أضف قائمة من اليمين أو أعدّها من هاتفك.",
    "Name (optional)" to "الاسم (اختياري)", "Xtream Codes login from your provider" to "بيانات Xtream Codes من مزود الخدمة",
    "Connecting..." to "جاري الاتصال...", "Fill server, username and password" to "املأ السيرفر واسم المستخدم وكلمة المرور",
    "Cancel" to "إلغاء", "✕ Remove playlist" to "✕ حذف القائمة", "Stop" to "إيقاف"
)
private val PRE = listOf(
    "Season " to "الموسم ", "Error: " to "خطأ: ", "Playback error: " to "خطأ في التشغيل: ", "Login failed: " to "فشل تسجيل الدخول: ",
    "Failed: " to "فشل: ", "Remove \"" to "حذف \"", "Connected: " to "متصل: ", "Stream format: " to "صيغة البث: ", "Added '" to "تمت إضافة '",
    "▶  Resume " to "▶  استكمال ", "▶  Continue " to "▶  متابعة ", "Aspect: " to "الأبعاد: ", "Audio: " to "الصوت: ", "Subtitles: " to "الترجمة: ", "Speed: " to "السرعة: "
)

fun tr(s: String): String {
    if (!Lang.ar) return s
    AR[s]?.let { return it }
    for ((p, a) in PRE) if (s.startsWith(p)) return a + s.substring(p.length)
    return s
}

fun nz(s: String): String {
    val b = StringBuilder(s.length)
    for (ch in s) when (ch) {
        in '\u064B'..'\u0652', '\u0640' -> {}
        'أ', 'إ', 'آ' -> b.append('ا')
        'ى' -> b.append('ي')
        'ة' -> b.append('ه')
        else -> b.append(ch.lowercaseChar())
    }
    return b.toString()
}

@Composable
fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
         fontWeight: FontWeight? = null, overflow: TextOverflow = TextOverflow.Clip, maxLines: Int = Int.MAX_VALUE) {
    androidx.tv.material3.Text(text = tr(text), modifier = modifier, color = color, fontSize = fontSize,
        fontWeight = fontWeight, overflow = overflow, maxLines = maxLines)
}
