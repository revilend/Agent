package com.revilend.ai.assistant.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.revilend.ai.assistant.ui.WebAppActivity
import java.io.File

/**
 * Generates, stores and launches self-contained HTML/CSS/JS mini applications
 * (websites, calculators, Snake-like games, clocks, to-do lists ...).
 *
 * The generated document is written to the app's private files directory and then
 * opened instantly - preferring an external browser (Chrome) and falling back to an
 * in-app [WebAppActivity] WebView when no browser can handle the file.
 */
class WebAppGenerator(private val context: Context) {

    companion object {
        private const val TAG = "WebAppGenerator"
        private const val DIR_NAME = "webapps"

        /** Rough keyword -> built-in template routing so the feature always works offline. */
        private val TEMPLATE_HINTS = mapOf(
            "snake" to "snake",
            "ilon" to "snake",
            "game" to "snake",
            "o'yin" to "snake",
            "oyin" to "snake",
            "calculator" to "calculator",
            "kalkulyator" to "calculator",
            "hisoblagich" to "calculator",
            "clock" to "clock",
            "soat" to "clock",
            "timer" to "clock",
            "todo" to "todo",
            "vazifa" to "todo",
            "notes" to "todo",
            "website" to "website",
            "sayt" to "website",
            "landing" to "website"
        )
    }

    /** Writes [html] to a file and returns it, or null on failure. */
    fun saveHtml(html: String, name: String): File? {
        return try {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val safe = name.lowercase()
                .replace(Regex("[^a-z0-9_-]"), "_")
                .ifBlank { "app" }
                .take(40)
            val file = File(dir, "$safe.html")
            file.writeText(html)
            Log.d(TAG, "Saved web app: ${file.absolutePath} (${html.length} chars)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
            null
        }
    }

    /**
     * Saves [html] and launches it immediately.
     * @return true when the content was displayed through some target.
     */
    fun launch(html: String, title: String): Boolean {
        val file = saveHtml(html, title.ifBlank { "revilend_app" })
        if (openInBrowser(file, html, title)) return true
        return openInWebViewActivity(file, html, title)
    }

    private fun openInBrowser(file: File?, html: String, title: String): Boolean {
        if (file == null) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                intent.setPackage("com.android.chrome")
                ContextCompat.startActivity(context, intent, null)
            } catch (e: Exception) {
                // Chrome missing - let the system pick any browser.
                intent.setPackage(null)
                ContextCompat.startActivity(context, intent, null)
            }
            Log.d(TAG, "Opened web app in browser: $title")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Browser launch failed: ${e.message}")
            false
        }
    }

    private fun openInWebViewActivity(file: File?, html: String, title: String): Boolean {
        return try {
            val intent = Intent(context, WebAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("html", html)
                putExtra("title", title)
                if (file != null) putExtra("path", file.absolutePath)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened web app in WebView activity: $title")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebView activity launch failed: ${e.message}")
            false
        }
    }

    /**
     * Returns the HTML to use for a request. When the LLM supplied usable HTML we use it,
     * otherwise we fall back to a built-in template matched from [request].
     */
    fun resolveHtml(request: String, llmHtml: String?): String {
        if (!llmHtml.isNullOrBlank() && llmHtml.trim().length > 40 &&
            llmHtml.contains("<", ignoreCase = true)
        ) {
            return llmHtml
        }
        return template(kindFor(request), request)
    }

    fun kindFor(request: String): String {
        val lower = request.lowercase()
        for ((hint, kind) in TEMPLATE_HINTS) {
            if (lower.contains(hint)) return kind
        }
        return "website"
    }

    // ---- Built-in, fully self-contained templates ----

    fun template(kind: String, request: String): String = when (kind) {
        "snake" -> snakeGame()
        "calculator" -> calculator()
        "clock" -> clockApp()
        "todo" -> todoApp()
        else -> website(request)
    }

    private fun head(title: String, extra: String = ""): String = """
        <!DOCTYPE html>
        <html lang="uz">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <title>$title</title>
        <style>
          * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
          html, body { margin: 0; padding: 0; min-height: 100%; }
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: radial-gradient(circle at 20% 0%, #101826 0%, #070b12 60%, #05070c 100%);
            color: #e6edf3; padding: 20px; display: flex; flex-direction: column; align-items: center;
          }
          h1 { font-size: 22px; font-weight: 700; letter-spacing: .5px; margin: 6px 0 14px;
               background: linear-gradient(90deg,#00e5ff,#6ee7ff); -webkit-background-clip: text;
               background-clip: text; color: transparent; text-align: center; }
          .card { width: 100%; max-width: 440px; background: rgba(18,24,33,.85);
                  border: 1px solid rgba(0,229,255,.22); border-radius: 16px; padding: 16px;
                  box-shadow: 0 10px 30px rgba(0,0,0,.45); }
          button { cursor: pointer; font-size: 17px; border-radius: 12px; border: 1px solid rgba(0,229,255,.3);
                   background: #12202c; color: #d6f7ff; padding: 12px; transition: .15s; }
          button:active { transform: scale(.96); background: #16303f; }
          .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
        </style>
        <style>$extra</style>
        </head>
        <body>
        <h1>$title</h1>
    """.trimIndent()

    private fun snakeGame(): String = head("Snake o'yini") + """
        <div class="card" style="display:flex;flex-direction:column;align-items:center;gap:10px">
          <div style="font-size:15px;color:#8bb7c9">Hisob: <b id="score">0</b></div>
          <canvas id="c" width="320" height="320" style="border-radius:12px;background:#070b12;border:1px solid rgba(0,229,255,.25)"></canvas>
          <div style="display:flex;gap:10px">
            <button id="up">▲</button><button id="left">◀</button>
            <button id="down">▼</button><button id="right">▶</button>
          </div>
          <button id="restart" style="width:100%">Qaytadan</button>
        </div>
        <script>
          var cv=document.getElementById('c'),ctx=cv.getContext('2d'),S=16,N=cv.width/S;
          var snake=[{x:8,y:8}],dir={x:1,y:0},food={x:4,y:4},score=0,dead=false,acc=0,last=0;
          function rand(){return {x:Math.floor(Math.random()*N),y:Math.floor(Math.random()*N)};}
          function reset(){snake=[{x:8,y:8}];dir={x:1,y:0};food=rand();score=0;dead=false;document.getElementById('score').textContent=0;}
          function turn(x,y){if(dead)return;if(dir.x===-x&&dir.y===-y)return;dir={x:x,y:y};}
          document.getElementById('up').onclick=function(){turn(0,-1);};
          document.getElementById('down').onclick=function(){turn(0,1);};
          document.getElementById('left').onclick=function(){turn(-1,0);};
          document.getElementById('right').onclick=function(){turn(1,0);};
          document.getElementById('restart').onclick=reset;
          document.addEventListener('keydown',function(e){
            if(e.key==='ArrowUp')turn(0,-1);if(e.key==='ArrowDown')turn(0,1);
            if(e.key==='ArrowLeft')turn(-1,0);if(e.key==='ArrowRight')turn(1,0);});
          var tsx=0,tsy=0;
          cv.addEventListener('touchstart',function(e){tsx=e.touches[0].clientX;tsy=e.touches[0].clientY;});
          cv.addEventListener('touchend',function(e){
            var dx=e.changedTouches[0].clientX-tsx,dy=e.changedTouches[0].clientY-tsy;
            if(Math.abs(dx)>Math.abs(dy))turn(dx>0?1:-1,0);else turn(0,dy>0?1:-1);});
          function step(){
            var h={x:(snake[0].x+dir.x+N)%N,y:(snake[0].y+dir.y+N)%N};
            for(var i=0;i<snake.length;i++){if(snake[i].x===h.x&&snake[i].y===h.y){dead=true;}}
            snake.unshift(h);
            if(h.x===food.x&&h.y===food.y){score++;document.getElementById('score').textContent=score;food=rand();}
            else snake.pop();
          }
          function draw(){
            ctx.clearRect(0,0,cv.width,cv.height);
            ctx.fillStyle='#00e5ff';ctx.fillRect(food.x*S+2,food.y*S+2,S-4,S-4);
            for(var i=0;i<snake.length;i++){ctx.fillStyle=i===0?'#6ee7ff':'#1f8ea8';ctx.fillRect(snake[i].x*S+1,snake[i].y*S+1,S-2,S-2);}
            if(dead){ctx.fillStyle='rgba(0,0,0,.6)';ctx.fillRect(0,0,cv.width,cv.height);
              ctx.fillStyle='#ff5d5d';ctx.font='bold 22px sans-serif';ctx.textAlign='center';
              ctx.fillText('O\'yin tugadi',cv.width/2,cv.height/2);}
          }
          function loop(ts){
            if(!last)last=ts;acc+=ts-last;last=ts;
            if(acc>130){acc=0;if(!dead)step();}
            draw();requestAnimationFrame(loop);
          }
          requestAnimationFrame(loop);
        </script>
        </body></html>
    """.trimIndent()

    private fun calculator(): String = head("Kalkulyator") + """
        <div class="card">
          <div id="disp" style="font-size:32px;text-align:right;padding:14px 10px;
            background:#0b131c;border-radius:12px;margin-bottom:12px;overflow:hidden;
            border:1px solid rgba(0,229,255,.15)">0</div>
          <div class="grid">
            <button data-k="C">C</button><button data-k="DEL">⌫</button>
            <button data-k="%">%</button><button data-k="/">÷</button>
            <button data-k="7">7</button><button data-k="8">8</button><button data-k="9">9</button><button data-k="*">×</button>
            <button data-k="4">4</button><button data-k="5">5</button><button data-k="6">6</button><button data-k="-">−</button>
            <button data-k="1">1</button><button data-k="2">2</button><button data-k="3">3</button><button data-k="+">+</button>
            <button data-k="0" style="grid-column:span 2">0</button><button data-k=".">.</button>
            <button data-k="=" style="background:#00b8d4;color:#04121a;font-weight:700">=</button>
          </div>
        </div>
        <script>
          var disp=document.getElementById('disp'),expr='';
          function show(){disp.textContent=expr||'0';}
          document.querySelectorAll('button').forEach(function(b){
            b.onclick=function(){
              var k=b.getAttribute('data-k');
              if(k==='C'){expr='';}
              else if(k==='DEL'){expr=expr.slice(0,-1);}
              else if(k==='='){try{expr=String(Function('return ('+expr+')')());}catch(e){expr='Xato';}}
              else{expr+=k;}
              show();
            };
          });
        </script>
        </body></html>
    """.trimIndent()

    private fun clockApp(): String = head("Soat") + """
        <div class="card" style="text-align:center">
          <div id="t" style="font-size:52px;font-weight:700;color:#00e5ff;letter-spacing:2px">--:--:--</div>
          <div id="d" style="color:#8bb7c9;margin-top:6px"></div>
        </div>
        <script>
          function tick(){var n=new Date();
            document.getElementById('t').textContent=n.toLocaleTimeString('uz-UZ');
            document.getElementById('d').textContent=n.toLocaleDateString('uz-UZ',{weekday:'long',day:'numeric',month:'long'});}
          tick();setInterval(tick,1000);
        </script>
        </body></html>
    """.trimIndent()

    private fun todoApp(): String = head("Vazifalar") + """
        <div class="card">
          <div style="display:flex;gap:8px;margin-bottom:12px">
            <input id="inp" placeholder="Yangi vazifa..." style="flex:1;padding:12px;border-radius:12px;
              border:1px solid rgba(0,229,255,.3);background:#0b131c;color:#e6edf3;font-size:16px">
            <button id="add">+</button>
          </div>
          <ul id="list" style="list-style:none;padding:0;margin:0"></ul>
        </div>
        <script>
          var inp=document.getElementById('inp'),list=document.getElementById('list');
          var items=[];
          function render(){list.innerHTML='';
            items.forEach(function(it,i){
              var li=document.createElement('li');
              li.style.cssText='display:flex;align-items:center;gap:10px;padding:10px;margin-bottom:6px;'+
                'background:#0b131c;border-radius:12px;border:1px solid rgba(0,229,255,.12)';
              var span=document.createElement('span');span.textContent=it.t;
              span.style.cssText='flex:1;'+(it.done?'text-decoration:line-through;color:#5b7280':'');
              span.onclick=function(){items[i].done=!items[i].done;render();};
              var del=document.createElement('button');del.textContent='🗑';
              del.onclick=function(){items.splice(i,1);render();};
              li.appendChild(span);li.appendChild(del);list.appendChild(li);
            });
          }
          function add(){var v=inp.value.trim();if(!v)return;items.push({t:v,done:false});inp.value='';render();}
          document.getElementById('add').onclick=add;
          inp.addEventListener('keydown',function(e){if(e.key==='Enter')add();});
        </script>
        </body></html>
    """.trimIndent()

    private fun website(request: String): String {
        val safeTitle = request.take(60).replace("<", "").replace(">", "")
        return head(safeTitle.ifBlank { "Revilend Web" }) + """
        <div class="card">
          <p style="color:#b9d6e2;line-height:1.6">Bu <b>Revilend AI</b> tomonidan yaratilgan sahifa.
          So'rovingiz: "${safeTitle}"</p>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px">
            <div style="padding:14px;border-radius:12px;background:#0b131c;border:1px solid rgba(0,229,255,.15)">
              <div style="font-weight:700;color:#00e5ff">Tezkor</div>
              <div style="color:#8bb7c9;font-size:14px;margin-top:4px">Bir zumda ochiladi</div>
            </div>
            <div style="padding:14px;border-radius:12px;background:#0b131c;border:1px solid rgba(0,229,255,.15)">
              <div style="font-weight:700;color:#00e5ff">Offline</div>
              <div style="color:#8bb7c9;font-size:14px;margin-top:4px">Internetsiz ishlaydi</div>
            </div>
          </div>
          <button id="hi" style="width:100%;margin-top:16px">Salomlash</button>
        </div>
        <script>document.getElementById('hi').onclick=function(){alert('Salom! Revilend AI bilan yaratildi.');};</script>
        </body></html>
        """.trimIndent()
    }
}
