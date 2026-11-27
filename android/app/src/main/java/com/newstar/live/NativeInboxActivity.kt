package com.newstar.live

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class NativeInboxActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val base = NewStarApi.BASE_URL
    private val key = NewStarApi.ANON_KEY
    private lateinit var access: String
    private lateinit var me: String
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var peer: String? = null
    private var peerName = "Creator"
    private var conversation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("newstar_native_session", MODE_PRIVATE)
        access = prefs.getString("access", null) ?: run { finish(); return }
        me = prefs.getString("user_id", null) ?: run { finish(); return }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8,10,18)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(12,8,12,8) }
        val back = Button(this).apply { text = "←"; isAllCaps = false; setOnClickListener { if (peer != null) showThreads() else finish() } }
        val title = TextView(this).apply { text = "Inbox"; textSize = 21f; setTextColor(Color.WHITE); setPadding(12,8,8,8) }
        header.addView(back); header.addView(title)
        root.addView(header)
        status = TextView(this).apply { setTextColor(Color.LTGRAY); setPadding(16,6,16,6) }
        root.addView(status)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12,8,12,16) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        showThreads()
    }

    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
    private fun call(path:String, method:String="GET", body:JSONObject?=null):String {
        val c=URL(base+path).openConnection() as HttpURLConnection
        try {
            c.requestMethod=method; c.connectTimeout=15000; c.readTimeout=20000
            c.setRequestProperty("apikey",key); c.setRequestProperty("Authorization","Bearer "+access)
            c.setRequestProperty("Accept","application/json")
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Prefer","return=minimal");c.outputStream.use{it.write(body.toString().toByteArray())}}
            val code=c.responseCode; val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()} ?: ""
            if(code !in 200..299) throw IllegalStateException("Server error ($code): "+raw.take(180))
            return raw
        } finally { c.disconnect() }
    }
    private fun showThreads() {
        peer=null; conversation=null; list.removeAllViews(); status.text="Loading conversations…"
        worker.execute {
            try {
                val rows=JSONArray(call("/rest/v1/messages?select=id,conversation_id,sender_id,recipient_id,body,created_at&or=(sender_id.eq.$me,recipient_id.eq.$me)&order=created_at.desc&limit=300"))
                val latest=LinkedHashMap<String,JSONObject>()
                for(i in 0 until rows.length()){
                    val m=rows.getJSONObject(i); val other=if(m.optString("sender_id")==me)m.optString("recipient_id") else m.optString("sender_id")
                    if(other.isNotBlank() && !latest.containsKey(other)) latest[other]=m
                }
                val ids=latest.keys.toList()
                val profiles=if(ids.isEmpty()) JSONArray() else JSONArray(call("/rest/v1/creator_public_profiles?select=user_id,username,full_name&user_id=in."+enc("("+ids.joinToString(",")+")")))
                val names=HashMap<String,String>()
                for(i in 0 until profiles.length()){val p=profiles.getJSONObject(i);names[p.optString("user_id")]=p.optString("full_name").ifBlank{p.optString("username").ifBlank{"New Star Creator"}}}
                runOnUiThread {
                    list.removeAllViews(); status.text=if(latest.isEmpty())"No conversations yet. Open a creator profile to start messaging." else "Messages"
                    latest.forEach { (id,m) ->
                        val row=Button(this).apply { isAllCaps=false; gravity=Gravity.START or Gravity.CENTER_VERTICAL; textSize=15f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24,27,40)); text=(names[id]?: "Creator")+"\n"+m.optString("body").ifBlank{"Media message"}; setPadding(16,14,16,14); setOnClickListener { openThread(id,names[id]?: "Creator",m.optString("conversation_id")) } }
                        list.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=8})
                    }
                }
            } catch(e:Exception){runOnUiThread{status.text=e.message?: "Could not load Inbox."}}
        }
    }
    private fun openThread(id:String,name:String,existingId:String) {
        peer=id; peerName=name; conversation=existingId
        if(conversation.isBlank()) conversation=null
        list.removeAllViews(); status.text="Loading chat with $name…"
        worker.execute {
            try {
                if(conversation==null) conversation=stableId(me,id)
                val conv=conversation!!
                val rows=JSONArray(call("/rest/v1/messages?select=id,sender_id,body,created_at&conversation_id=eq.$conv&order=created_at.asc&limit=300"))
                runOnUiThread {
                    if(peer!=id)return@runOnUiThread
                    status.text=name
                    for(i in 0 until rows.length()){val m=rows.getJSONObject(i); val mine=m.optString("sender_id")==me; val bubble=TextView(this).apply{ text=m.optString("body"); textSize=15f; setTextColor(Color.WHITE); setPadding(14,10,14,10); setBackgroundColor(if(mine)Color.rgb(90,55,170) else Color.rgb(35,38,48)); gravity=if(mine)Gravity.END else Gravity.START }
                        list.addView(bubble,LinearLayout.LayoutParams(-1,-2).apply{setMargins(if(mine)48 else 0,4,if(mine)0 else 48,4)}) }
                    composer.visibility=android.view.View.VISIBLE
                    sendButton.setOnClickListener {
                        val body=messageInput.text.toString().trim(); if(body.isBlank())return@setOnClickListener
                        sendButton.isEnabled=false
                        worker.execute { try {
                            call("/rest/v1/messages","POST",JSONObject().put("conversation_id",conv).put("sender_id",me).put("recipient_id",id).put("body",body))
                            runOnUiThread { messageInput.setText(""); sendButton.isEnabled=true; openThread(id,name,conv) }
                        } catch(e:Exception){runOnUiThread{sendButton.isEnabled=true;status.text=e.message?: "Message send failed."}} }
                    }
                }
            } catch(e:Exception){runOnUiThread{status.text=e.message?: "Could not load conversation."}}
        }
    }
    private fun stableId(a:String,b:String):String {
        val pair=listOf(a,b).sorted().joinToString(":")
        val bytes=java.security.MessageDigest.getInstance("SHA-256").digest(("new-star-dm:"+pair).toByteArray())
        val hex=bytes.take(16).joinToString(""){"%02x".format(it)}
        return hex.substring(0,8)+"-"+hex.substring(8,12)+"-5"+hex.substring(13,16)+"-a"+hex.substring(17,20)+"-"+hex.substring(20,32)
    }
    override fun onDestroy(){worker.shutdown();super.onDestroy()}
}
