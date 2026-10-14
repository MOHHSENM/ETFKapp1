package ir.etfk.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import android.widget.ImageView

class InvoiceListActivity : BaseActivity() {

    private lateinit var api: InvoiceApi
    private lateinit var adapter: InvoiceRowAdapter
    private var lastRows: List<InvoiceRow> = emptyList()

    private val statusLabels = listOf(
        "همه", "ثبت موقت", "ثبت آیتم", "ارسال پیامک",
        "قسط بندی شده", "انصراف", "برگشتی", "تسویه"
    )
    private val statusCodes = listOf(
        " ", "wait", "item", "send_sms",
        "ok", "no", "reject", "tasvieh"
    )

    private val persianMonths = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_list)
        api = InvoiceApi(session)
        findViewById<ImageView>(R.id.btnBackHeader).setOnClickListener { finish() }

        val today = todayPersian()
        val etStart = findViewById<EditText>(R.id.etStart)
        val etEnd = findViewById<EditText>(R.id.etEnd)
        etStart.setText(today)
        etEnd.setText(today)

        etStart.setOnClickListener { showDatePicker(etStart.text.toString()) { etStart.setText(it) } }
        etEnd.setOnClickListener { showDatePicker(etEnd.text.toString()) { etEnd.setText(it) } }

        val spStatus = findViewById<Spinner>(R.id.spStatus)
        spStatus.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            statusLabels
        )
        // ⚡ پیش‌فرض روی «قسط بندی شده» (ایندکس ۴)
        spStatus.setSelection(4)

        adapter = InvoiceRowAdapter(
            emptyList(),
            onClick = { row ->
                toast("کد فاکتور: ${row.id}\nوضعیت: ${row.status}")
            },
            onCancel = { row, position ->
                confirmCancel(row, position)
            },
            onReturn = { row ->
                val intent = android.content.Intent(this, ReturnInvoiceActivity::class.java)
                intent.putExtra("factor_code", row.id)
                intent.putExtra("personel_code", row.personel)
                // کد ملی دیگه پاس داده نمیشه
                startActivity(intent)
            }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@InvoiceListActivity)
            adapter = this@InvoiceListActivity.adapter
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener { doSearch() }
        findViewById<Button>(R.id.btnPrintList).setOnClickListener { showPdfPreview() }

        openList()
    }
    private fun confirmCancel(row: InvoiceRow, position: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("انصراف از فاکتور")
            .setMessage("آیا مایل به انصراف هستید؟\nکد فاکتور: ${row.id}")
            .setPositiveButton("بله") { _, _ ->
                showLoading(true)
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { api.cancelInvoiceAt(position) }
                    showLoading(false)
                    r.onSuccess {
                        toast("فاکتور با موفقیت لغو شد")
                        doSearch()
                    }.onFailure {
                        toast(it.message ?: "خطا در لغو فاکتور")
                    }
                }
            }
            .setNegativeButton("خیر", null)
            .show()
    }
    private fun showDatePicker(initial: String, onPicked: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_persian_date, null)
        val npYear = view.findViewById<NumberPicker>(R.id.npYear)
        val npMonth = view.findViewById<NumberPicker>(R.id.npMonth)
        val npDay = view.findViewById<NumberPicker>(R.id.npDay)

        val parts = initial.split("/")
        val y0 = parts.getOrNull(0)?.toIntOrNull() ?: 1405
        val m0 = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val d0 = parts.getOrNull(2)?.toIntOrNull() ?: 1

        npYear.minValue = 1390
        npYear.maxValue = 1420
        npYear.value = y0.coerceIn(1390, 1420)
        npMonth.minValue = 1
        npMonth.maxValue = 12
        npMonth.displayedValues = persianMonths
        npMonth.value = m0.coerceIn(1, 12)
        npDay.minValue = 1
        npDay.maxValue = 31
        npDay.value = d0.coerceIn(1, 31)

        android.app.AlertDialog.Builder(this)
            .setTitle("انتخاب تاریخ")
            .setView(view)
            .setPositiveButton("تایید") { _, _ ->
                val y = npYear.value
                val m = npMonth.value
                val d = npDay.value
                val ms = if (m < 10) "0$m" else "$m"
                val ds = if (d < 10) "0$d" else "$d"
                onPicked("$y/$ms/$ds")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun openList() {
        showLoading(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { api.openInvoiceList() }
            showLoading(false)
            r.onSuccess { doSearch() }
                .onFailure { toast(it.message ?: "خطا در باز کردن لیست"); finish() }
        }
    }
    // تبدیل ارقام فارسی/عربی به لاتین
    private fun normalizeDigits(input: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        for (ch in input) {
            val pi = persian.indexOf(ch)
            val ai = arabic.indexOf(ch)
            when {
                pi >= 0 -> sb.append(pi.toString())
                ai >= 0 -> sb.append(ai.toString())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
    private fun doSearch() {
        val start = normalizeDigits(findViewById<EditText>(R.id.etStart).text.toString().trim())
        val end = normalizeDigits(findViewById<EditText>(R.id.etEnd).text.toString().trim())
        val factorCode = findViewById<EditText>(R.id.etFactorCode).text.toString().trim()
        val shopInvoice = findViewById<EditText>(R.id.etShopInvoice).text.toString().trim()
        val meli = findViewById<EditText>(R.id.etMeli).text.toString().trim()
        val shoghl = findViewById<EditText>(R.id.etShoghl).text.toString().trim()

        val statusIndex = findViewById<Spinner>(R.id.spStatus).selectedItemPosition
        val status = statusCodes.getOrElse(statusIndex) { " " }

        if (start.isEmpty() || end.isEmpty()) {
            toast("تاریخ شروع و پایان را وارد کنید"); return
        }

        showLoading(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                api.searchInvoices(start, end, factorCode, shopInvoice, meli, shoghl, status)
            }
            showLoading(false)
            r.onSuccess { rows ->
                lastRows = rows
                adapter.update(rows)
                updateTotal(rows)
            }.onFailure { toast(it.message ?: "خطا در جستجو") }
        }
    }

    private fun updateTotal(rows: List<InvoiceRow>) {
        val sum = rows.sumOf { row ->
            row.price.replace(",", "").replace(" ", "").toLongOrNull() ?: 0L
        }
        findViewById<TextView>(R.id.txtTotal).text = "جمع کل: ${"%,d".format(sum)} ریال"
    }

    // ============ پیش‌نمایش PDF ============
    @SuppressLint("SetJavaScriptEnabled")
    private fun showPdfPreview() {
        if (lastRows.isEmpty()) {
            toast("لیستی برای چاپ وجود ندارد"); return
        }

        val html = buildPrintHtml() ?: run {
            toast("خطا در ساخت قالب"); return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pdf_preview)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val webView = dialog.findViewById<WebView>(R.id.webViewPdf)
        val btnSave = dialog.findViewById<Button>(R.id.btnSavePdf)
        val btnClose = dialog.findViewById<Button>(R.id.btnClosePreview)
        val progress = dialog.findViewById<ProgressBar>(R.id.progressPdf)

        webView.settings.javaScriptEnabled = true
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)

        val fileName = "invoices_${todayPersian().replace("/", "-")}"

        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            progress.visibility = View.VISIBLE
            btnSave.isEnabled = false

            val start = findViewById<EditText>(R.id.etStart).text.toString()
            val end = findViewById<EditText>(R.id.etEnd).text.toString()
            val rowsCopy = lastRows.toList()

            Thread {
                val file = generateDirectPdf(rowsCopy, start, end, fileName)
                runOnUiThread {
                    progress.visibility = View.GONE
                    btnSave.isEnabled = true
                    if (file != null) {
                        toast("✅ PDF ذخیره شد: ${file.name}\nدر پوشه Downloads")
                    } else {
                        toast("خطا در ذخیره PDF")
                    }
                }
            }.start()
        }

        dialog.show()
    }

    private fun buildPrintHtml(): String? {
        return try {
            val template = assets.open("print_list.html").bufferedReader().use { it.readText() }

            val sum = lastRows.sumOf { row ->
                row.price.replace(",", "").replace(" ", "").toLongOrNull() ?: 0L
            }

            val rowsBuilder = StringBuilder()
            lastRows.forEachIndexed { idx, r ->
                rowsBuilder.append("<tr>")
                rowsBuilder.append("<td>${idx + 1}</td>")
                rowsBuilder.append("<td>${r.id}</td>")
                rowsBuilder.append("<td>${r.date}</td>")
                rowsBuilder.append("<td>${r.detail.trim()}</td>")
                rowsBuilder.append("<td>${r.price}</td>")
                rowsBuilder.append("<td>${r.installments}</td>")
                rowsBuilder.append("<td>${r.customer}</td>")
                rowsBuilder.append("<td>${r.status}</td>")
                rowsBuilder.append("</tr>")
            }

            val start = findViewById<EditText>(R.id.etStart).text.toString()
            val end = findViewById<EditText>(R.id.etEnd).text.toString()

            template
                .replace("<p id=\"subtitle\"></p>", "<p id=\"subtitle\">از $start تا $end</p>")
                .replace("<tbody id=\"rows\"></tbody>", "<tbody id=\"rows\">$rowsBuilder</tbody>")
                .replace("<td id=\"totalAmount\"></td>", "<td id=\"totalAmount\">${"%,d".format(sum)} ریال</td>")
                .replace("<span id=\"printDate\"></span>", "<span id=\"printDate\">${todayPersian()}</span>")
        } catch (e: Exception) {
            null
        }
    }

    // ============ تولید PDF مستقیم با Canvas ============
    private fun generateDirectPdf(
        rows: List<InvoiceRow>,
        start: String,
        end: String,
        fileName: String
    ): File? {
        return try {
            val pdf = PdfDocument()

            val pageWidth = 842
            val pageHeight = 595
            val margin = 20f
            val headerH = 45f
            val tableHeaderH = 28f
            val lineHeight = 13f
            val cellPadding = 5f
            val maxLines = 3
            val totalRowH = 28f

            // ستون‌ها از راست به چپ: ردیف راست‌ترین، پیش پرداخت چپ‌ترین
            val colWidths = floatArrayOf(
                30f,   // ردیف
                65f,   // کد
                100f,  // تاریخ
                160f,  // شرح
                78f,   // مبلغ
                45f,   // اقساط
                150f,  // خریدار
                78f,   // وضعیت
                78f    // پیش پرداخت
            )
            val headers = arrayOf("ردیف", "کد", "تاریخ", "شرح", "مبلغ", "اقساط", "خریدار", "وضعیت", "پیش پرداخت")

            val paintText = Paint().apply {
                color = Color.BLACK
                textSize = 10f
                isAntiAlias = true
            }
            val paintBold = Paint().apply {
                color = Color.BLACK
                textSize = 11f
                isAntiAlias = true
                isFakeBoldText = true
            }
            val paintTitle = Paint().apply {
                color = Color.parseColor("#1A237E")
                textSize = 15f
                isAntiAlias = true
                isFakeBoldText = true
            }
            val paintBorder = Paint().apply {
                color = Color.parseColor("#555555")
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            val paintFillHeader = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                style = Paint.Style.FILL
            }
            val paintFillTotal = Paint().apply {
                color = Color.parseColor("#FFF3E0")
                style = Paint.Style.FILL
            }

            fun wrapText(text: String, maxWidth: Float, p: Paint): List<String> {
                if (text.isEmpty()) return listOf("")
                val lines = mutableListOf<String>()
                val words = text.split(" ")
                var current = StringBuilder()
                for (word in words) {
                    val test = if (current.isEmpty()) word else "$current $word"
                    if (p.measureText(test) <= maxWidth) {
                        if (current.isNotEmpty()) current.append(" ")
                        current.append(word)
                    } else {
                        if (current.isNotEmpty()) {
                            lines.add(current.toString())
                            current = StringBuilder(word)
                        } else {
                            var temp = ""
                            for (ch in word) {
                                if (p.measureText(temp + ch) <= maxWidth) temp += ch
                                else {
                                    lines.add(temp)
                                    temp = ch.toString()
                                }
                            }
                            current = StringBuilder(temp)
                        }
                    }
                }
                if (current.isNotEmpty()) lines.add(current.toString())
                if (lines.isEmpty()) lines.add("")
                return lines
            }

            fun rowHeight(cells: Array<String>): Float {
                var maxC = 1
                for (i in cells.indices) {
                    val maxW = colWidths[i] - cellPadding * 2
                    val c = wrapText(cells[i], maxW, paintText).size.coerceAtMost(maxLines)
                    if (c > maxC) maxC = c
                }
                return maxC * lineHeight + cellPadding * 2
            }

            val totalAmount = rows.sumOf { r ->
                r.price.replace(",", "").replace(" ", "").toLongOrNull() ?: 0L
            }
            val totalPrepayment = rows.sumOf { r ->
                r.prepayment.replace(",", "").replace(" ", "").toLongOrNull() ?: 0L
            }

            data class RowItem(val cells: Array<String>, val h: Float)
            val items = rows.mapIndexed { idx, r ->
                val cells = arrayOf(
                    (idx + 1).toString(),
                    r.id, r.date, r.detail.trim(),
                    r.price, r.installments, r.customer, r.status, r.prepayment
                )
                RowItem(cells, rowHeight(cells))
            }

            var rowIdx = 0
            var pageNum = 1

            while (rowIdx < items.size) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas

                var y = margin

                // عنوان وسط‌چین
                val titleText = "لیست فاکتورهای اقساطی"
                val tw = paintTitle.measureText(titleText)
                canvas.drawText(titleText, (pageWidth - tw) / 2f, y + 18f, paintTitle)

                val subText = "از $start تا $end"
                val sw = paintText.measureText(subText)
                canvas.drawText(subText, (pageWidth - sw) / 2f, y + 36f, paintText)
                y += headerH

                // هدر جدول - از راست به چپ
                canvas.drawRect(margin, y, pageWidth - margin, y + tableHeaderH, paintFillHeader)
                var xr = pageWidth - margin
                for (i in headers.indices) {
                    val w = colWidths[i]
                    xr -= w
                    canvas.drawRect(xr, y, xr + w, y + tableHeaderH, paintBorder)
                    val hw = paintBold.measureText(headers[i])
                    canvas.drawText(headers[i], xr + (w - hw) / 2f, y + tableHeaderH - 10f, paintBold)
                }
                y += tableHeaderH

                // سطرها
                while (rowIdx < items.size) {
                    val item = items[rowIdx]
                    if (y + item.h > pageHeight - margin - totalRowH) break

                    var x = pageWidth - margin
                    for (i in item.cells.indices) {
                        val w = colWidths[i]
                        x -= w
                        canvas.drawRect(x, y, x + w, y + item.h, paintBorder)

                        val maxW = w - cellPadding * 2
                        val lines = wrapText(item.cells[i], maxW, paintText)
                        val display = lines.take(maxLines)

                        var ly = y + cellPadding + lineHeight - 4f
                        for ((li, line) in display.withIndex()) {
                            var t = line
                            if (li == maxLines - 1 && lines.size > maxLines) {
                                while (paintText.measureText("$t…") > maxW && t.length > 1) {
                                    t = t.dropLast(1)
                                }
                                t = "$t…"
                            }
                            val lw = paintText.measureText(t)
                            val lx = x + w - lw - cellPadding
                            canvas.drawText(t, lx, ly, paintText)
                            ly += lineHeight
                        }
                    }
                    y += item.h
                    rowIdx++
                }

                // ===== ردیف جمع کل دقیقاً زیر جدول =====
                canvas.drawRect(margin, y, pageWidth - margin, y + totalRowH, paintFillTotal)

                // کادر همه ستون‌ها برای ردیف جمع
                var xr2 = pageWidth - margin
                for (i in colWidths.indices) {
                    xr2 -= colWidths[i]
                    canvas.drawRect(xr2, y, xr2 + colWidths[i], y + totalRowH, paintBorder)
                }

                // متن "جمع کل" توی ناحیه ۴ ستون اول از راست (ردیف+کد+تاریخ+شرح)
                val rightEdge = pageWidth - margin
                val labelWidth = colWidths[0] + colWidths[1] + colWidths[2] + colWidths[3]
                val labelLeft = rightEdge - labelWidth
                val labelText = "جمع کل"
                val lw = paintBold.measureText(labelText)
                val labelCenterX = labelLeft + labelWidth / 2f
                canvas.drawText(labelText, labelCenterX - lw / 2f, y + totalRowH - 10f, paintBold)

                // مقدار جمع توی ستون مبلغ
                val mablaghRight = labelLeft
                val mablaghLeft = mablaghRight - colWidths[4]
                val amountText = "${"%,d".format(totalAmount)}"
                val aw = paintBold.measureText(amountText)
                canvas.drawText(amountText, mablaghRight - aw - 5f, y + totalRowH - 10f, paintBold)

                // جمع پیش پرداخت توی ستون پیش پرداخت (چپ‌ترین ستون)
                val prepayRight = margin + colWidths[8]
                val prepayLeft = margin
                val prepayText = "${"%,d".format(totalPrepayment)}"
                val pw = paintBold.measureText(prepayText)
                canvas.drawText(prepayText, prepayRight - pw - 5f, y + totalRowH - 10f, paintBold)

                y += totalRowH

                pdf.finishPage(page)
                pageNum++
            }

            val file = File(cacheDir, "$fileName.pdf")
            FileOutputStream(file).use { out ->
                pdf.writeTo(out)
            }
            pdf.close()

            copyToDownloads(file, fileName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // کپی فایل PDF به پوشه Downloads
    private fun copyToDownloads(source: File, fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    File(getExternalFilesDir(null), "$fileName.pdf")
                } else null
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, "$fileName.pdf")
                FileOutputStream(outFile).use { out ->
                    source.inputStream().use { inp -> inp.copyTo(out) }
                }
                outFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showLoading(show: Boolean) {
        findViewById<ProgressBar>(R.id.progress).visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun todayPersian(): String {
        val c = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
        // ⚡ اجبار به استفاده از ارقام لاتین
        val m = if (jm < 10) "0$jm" else "$jm"
        val d = if (jd < 10) "0$jd" else "$jd"
        return "$jy/$m/$d"
    }

    private fun gregorianToJalali(gy0: Int, gm0: Int, gd0: Int): Triple<Int, Int, Int> {
        var gy = gy0
        val gm = gm0
        val gd = gd0
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy: Int
        if (gy > 1600) {
            jy = 979
            gy -= 1600
        } else {
            jy = 0
            gy -= 621
        }
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 - 80 + gd + gdm[gm - 1]
        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }
}

// ============ Adapter ============
class InvoiceRowAdapter(
    private var items: List<InvoiceRow>,
    private val onClick: (InvoiceRow) -> Unit,
    private val onCancel: (InvoiceRow, Int) -> Unit,
    private val onReturn: (InvoiceRow) -> Unit
) : RecyclerView.Adapter<InvoiceRowAdapter.VH>() {

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<InvoiceRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position, onClick, onCancel, onReturn)
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtFactorCode: TextView = v.findViewById(R.id.txtFactorCode)
        val txtDate: TextView = v.findViewById(R.id.txtDate)
        val txtDetail: TextView = v.findViewById(R.id.txtDetail)
        val txtTerminal: TextView = v.findViewById(R.id.txtTerminal)
        val txtShopInvoice: TextView = v.findViewById(R.id.txtShopInvoice)
        val txtPrice: TextView = v.findViewById(R.id.txtPrice)
        val txtInstallments: TextView = v.findViewById(R.id.txtInstallments)
        val txtPrepayment: TextView = v.findViewById(R.id.txtPrepayment)
        val txtCustomer: TextView = v.findViewById(R.id.txtCustomer)
        val txtMeli: TextView = v.findViewById(R.id.txtMeli)
        val txtPersonel: TextView = v.findViewById(R.id.txtPersonel)
        val txtStatus: TextView = v.findViewById(R.id.txtStatus)
        val btnCancel: ImageView = v.findViewById(R.id.btnCancelInvoice)
        val btnReturn: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnReturnInvoice)

        fun bind(
            row: InvoiceRow,
            position: Int,
            onClick: (InvoiceRow) -> Unit,
            onCancel: (InvoiceRow, Int) -> Unit,
            onReturn: (InvoiceRow) -> Unit
        ) {
            txtFactorCode.text = "کد: ${row.id}"
            txtDate.text = row.date
            txtDetail.text = row.detail.trim()
            txtTerminal.text = "فروشگاه: ${row.terminal}"
            txtShopInvoice.text = "شماره داخلی: ${row.shopInvoice}"
            txtPrice.text = "${row.price} ریال"
            txtInstallments.text = "اقساط: ${row.installments}"
            txtPrepayment.text = "پیش پرداخت: ${row.prepayment} ریال"
            txtCustomer.text = row.customer
            txtMeli.text = "کد ملی: ${row.meli}"
            txtPersonel.text = "کد پرسنلی: ${row.personel}"
            txtStatus.text = row.status

            // دکمه ضربدر فقط برای «ارسال پیامک»
            if (row.status.contains("ارسال")) {
                btnCancel.visibility = View.VISIBLE
                btnCancel.setOnClickListener { onCancel(row, position) }
            } else {
                btnCancel.visibility = View.GONE
                btnCancel.setOnClickListener(null)
            }

            // دکمه برگشت فقط برای «قسط بندی»
            if (row.status.contains("قسط")) {
                btnReturn.visibility = View.VISIBLE
                btnReturn.setOnClickListener { onReturn(row) }
            } else {
                btnReturn.visibility = View.GONE
                btnReturn.setOnClickListener(null)
            }

            val bgColor = when {
                row.status.contains("قسط") -> "#E8F5E9"
                row.status.contains("برگشت") -> "#FFEBEE"
                row.status.contains("انصراف") -> "#FFF3E0"
                row.status.contains("ارسال") -> "#FFF9C4"
                else -> "#E3F2FD"
            }
            val fgColor = when {
                row.status.contains("قسط") -> "#2E7D32"
                row.status.contains("برگشت") -> "#C62828"
                row.status.contains("انصراف") -> "#E65100"
                row.status.contains("ارسال") -> "#F57F17"
                else -> "#0D47A1"
            }
            txtStatus.setBackgroundColor(android.graphics.Color.parseColor(bgColor))
            txtStatus.setTextColor(android.graphics.Color.parseColor(fgColor))

            itemView.setOnClickListener { onClick(row) }
        }
    }
}