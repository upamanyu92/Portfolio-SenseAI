package ai.portfoliosense

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal data class LedgerEntry(val id:Long,val sender:String,val at:Long,val paise:Long,val kind:FlowKind)
internal data class MonthLedger(val totals:Map<FlowKind,Long>,val entries:List<LedgerEntry>,val count:Int)

internal object SmsLedger {
    val changes=MutableStateFlow(0L)
    private var helper:SQLiteOpenHelper?=null
    @Synchronized private fun db(c:Context):SQLiteDatabase {
        if(helper==null) helper=object:SQLiteOpenHelper(c.applicationContext,"cash-ledger.db",null,1) {
            override fun onCreate(db:SQLiteDatabase) {
                db.execSQL("CREATE TABLE entry (sms_id INTEGER PRIMARY KEY, sender TEXT NOT NULL, at_ms INTEGER NOT NULL, paise INTEGER NOT NULL, kind TEXT NOT NULL, dedup TEXT UNIQUE)")
                db.execSQL("CREATE INDEX entry_date ON entry(at_ms)")
                db.execSQL("CREATE TABLE progress (id INTEGER PRIMARY KEY, last_sms INTEGER NOT NULL)")
                db.execSQL("INSERT INTO progress VALUES(1,0)")
            }
            override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {}
        }
        return helper!!.writableDatabase
    }
    private fun prefs(c:Context)=c.getSharedPreferences("sms-tracking",Context.MODE_PRIVATE)
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
    fun permitted(c:Context,p:String)=c.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    fun setEnabled(c:Context,on:Boolean) {
        prefs(c).edit().putBoolean("enabled",on).apply()
        val wm=WorkManager.getInstance(c)
        if(on) {
            enqueue(c,false)
            wm.enqueueUniquePeriodicWork("ledger-catchup",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LedgerWorker>(6,TimeUnit.HOURS).addTag("ledger-sync").build())
        } else wm.cancelAllWorkByTag("ledger-sync")
    }
    fun enqueue(c:Context,delay:Boolean) {
        if(!enabled(c)||!permitted(c,Manifest.permission.READ_SMS)) return
        val job=OneTimeWorkRequestBuilder<LedgerWorker>().setInitialDelay(if(delay) 3L else 0L,TimeUnit.SECONDS).addTag("ledger-sync").build()
        WorkManager.getInstance(c).enqueueUniqueWork(if(delay)"ledger-incoming" else "ledger-import",ExistingWorkPolicy.APPEND_OR_REPLACE,job)
    }

    @Synchronized fun importInbox(c:Context,stopped:()->Boolean) {
        if(!enabled(c)||!permitted(c,Manifest.permission.READ_SMS)) return
        val db=db(c)
        val last=db.rawQuery("SELECT last_sms FROM progress WHERE id=1",null).use { it.moveToFirst();it.getLong(0) }
        val cursor=c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("_id","address","body","date"),
            "_id > ?",arrayOf(last.toString()),"_id ASC") ?: error("SMS inbox unavailable")
        cursor.use {
            while(it.moveToNext()) {
                db.beginTransaction()
                try {
                    var count=0
                    do {
                        if(stopped() || !enabled(c)) return
                        val id=it.getLong(0)
                        val from=it.getString(1).orEmpty()
                        val alert=AlertParser.parse(from,it.getString(2).orEmpty())
                        if(alert!=null) {
                            val v=ContentValues().apply {
                                put("sms_id",id);put("sender",from);put("at_ms",it.getLong(3))
                                put("paise",alert.paise);put("kind",alert.kind.name)
                                // Matching reference, direction and amount collapses repeated transaction alerts.
                                if(alert.reference!=null) put("dedup","${alert.kind}|${alert.paise}|${alert.reference}")
                            }
                            db.insertWithOnConflict("entry",null,v,SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        db.execSQL("UPDATE progress SET last_sms=? WHERE id=1",arrayOf(id))
                        count++
                    } while(count<200 && it.moveToNext())
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                changes.value++
            }
        }
    }
    fun month(c:Context,month:YearMonth,limit:Int):MonthLedger {
        fun start(m:YearMonth)=m.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        val db=db(c)
        val args=arrayOf(start(month),start(month.plusMonths(1)))
        val totals=mutableMapOf<FlowKind,Long>()
        var count=0
        db.rawQuery("SELECT kind,SUM(paise),COUNT(*) FROM entry WHERE at_ms>=? AND at_ms<? GROUP BY kind",args).use {
            while(it.moveToNext()) { totals[FlowKind.valueOf(it.getString(0))]=it.getLong(1);count+=it.getInt(2) }
        }
        val entries=mutableListOf<LedgerEntry>()
        db.rawQuery("SELECT sms_id,sender,at_ms,paise,kind FROM entry WHERE at_ms>=? AND at_ms<? ORDER BY at_ms DESC,sms_id DESC LIMIT ?",args+limit.toString()).use {
            while(it.moveToNext()) entries+=LedgerEntry(it.getLong(0),it.getString(1),it.getLong(2),it.getLong(3),FlowKind.valueOf(it.getString(4)))
        }
        return MonthLedger(totals,entries,count)
    }
    fun classify(c:Context,id:Long,kind:FlowKind) {
        db(c).update("entry",ContentValues().apply { put("kind",kind.name) },"sms_id=?",arrayOf(id.toString()))
        changes.value++
    }
}

class LedgerWorker(c:Context,p:WorkerParameters):Worker(c,p) {
    override fun doWork():Result = try {
        SmsLedger.importInbox(applicationContext){isStopped}; Result.success()
    } catch(e:SecurityException) { Result.failure() }
      catch(e:Exception) { if(runAttemptCount<3) Result.retry() else Result.failure() }
}

class BankSmsReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==Telephony.Sms.Intents.SMS_RECEIVED_ACTION) SmsLedger.enqueue(context,true)
    }
}
