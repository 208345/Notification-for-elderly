/**
 * みまもりリマインダー ― Google スプレッドシート側のプログラム
 *
 * 【使いかた】
 *  1. Google スプレッドシートを新規作成する
 *  2. メニューの「拡張機能」→「Apps Script」を開く
 *  3. 出てきたエディタの中身を全部消して、このファイルの内容を貼り付ける
 *  4. 右上の「デプロイ」→「新しいデプロイ」
 *       種類 : ウェブアプリ
 *       実行するユーザー : 自分
 *       アクセスできるユーザー : 全員          ← ここが「自分のみ」だと動きません
 *  5. 表示された「ウェブアプリのURL」をコピーして、アプリの設定画面に貼り付ける
 *
 * 【できること】
 *  ・アプリから送られた完了／未完了が「記録」シートにたまります
 *  ・同じURLをブラウザで開くと、今日の状況が見やすい画面で表示されます
 */

var LOG_SHEET = '記録';
var HEADERS = ['受信日時', '名前', '日付', '予定時刻', '内容', '状態', 'タスクID'];

/** アプリからの送信を受け取る */
function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    var events = body.events || [];
    var sheet = getLogSheet_();

    var rows = events.map(function (ev) {
      return [
        ev.at || '',
        ev.person || '',
        ev.date || '',
        ev.scheduled || '',
        ev.title || '',
        statusLabel_(ev.status),
        ev.taskId || '',
      ];
    });

    if (rows.length > 0) {
      sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADERS.length).setValues(rows);
    }

    return ContentService
      .createTextOutput(JSON.stringify({ ok: true, saved: rows.length }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService
      .createTextOutput(JSON.stringify({ ok: false, error: String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

/** 同じURLをブラウザで開いたときに出る、ご家族向けの画面 */
function doGet(e) {
  var days = Number((e && e.parameter && e.parameter.days) || 7);
  var sheet = getLogSheet_();
  var values = sheet.getDataRange().getValues();
  var tz = Session.getScriptTimeZone() || 'Asia/Tokyo';

  // ヘッダーを除いて、日付+タスクIDごとに「いちばん新しい状態」を残す
  var latest = {};
  for (var i = 1; i < values.length; i++) {
    var r = values[i];
    var date = String(r[2]);
    if (!date) continue;
    var key = date + '|' + r[6] + '|' + r[4];
    latest[key] = {
      person: r[1], date: date, scheduled: String(r[3]),
      title: String(r[4]), status: String(r[5]), at: String(r[0]),
    };
  }

  var byDate = {};
  Object.keys(latest).forEach(function (k) {
    var v = latest[k];
    if (!byDate[v.date]) byDate[v.date] = [];
    byDate[v.date].push(v);
  });

  var dates = Object.keys(byDate).sort().reverse().slice(0, days);
  var html = renderHtml_(dates, byDate, tz);

  return HtmlService.createHtmlOutput(html)
    .setTitle('みまもり状況')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

// ------------------------------------------------------------

function getLogSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(LOG_SHEET);
  if (!sheet) {
    sheet = ss.insertSheet(LOG_SHEET);
  }
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]);
    sheet.getRange(1, 1, 1, HEADERS.length).setFontWeight('bold').setBackground('#E8F5E9');
    sheet.setFrozenRows(1);
    sheet.setColumnWidth(1, 160);
    sheet.setColumnWidth(5, 220);
  }
  return sheet;
}

function statusLabel_(s) {
  switch (s) {
    case 'done': return '完了';
    case 'missed': return '未完了';
    case 'undone': return '取り消し';
    case 'test': return 'テスト';
    default: return s || '';
  }
}

function renderHtml_(dates, byDate, tz) {
  var css =
    'body{font-family:-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;margin:0;padding:16px;' +
    'background:#F7F9F7;color:#15202B;max-width:640px;margin:0 auto}' +
    'h1{font-size:20px;margin:8px 0 4px}' +
    '.sub{color:#5B6770;font-size:13px;margin-bottom:18px}' +
    '.day{background:#fff;border:1px solid #DDE3E8;border-radius:14px;padding:14px 16px;margin-bottom:14px}' +
    '.dayhead{display:flex;align-items:baseline;gap:10px;margin-bottom:10px}' +
    '.date{font-size:18px;font-weight:800}' +
    '.count{font-size:13px;color:#5B6770}' +
    '.row{display:flex;align-items:center;gap:10px;padding:7px 0;border-top:1px solid #F0F3F5}' +
    '.row:first-of-type{border-top:none}' +
    '.time{font-weight:800;width:62px;font-variant-numeric:tabular-nums}' +
    '.title{flex:1}' +
    '.badge{font-size:12px;font-weight:700;padding:3px 10px;border-radius:999px}' +
    '.ok{background:#E3F3E6;color:#1B5E20}' +
    '.ng{background:#FDEAEA;color:#B3261E}' +
    '.other{background:#EEF1F4;color:#5B6770}';

  var parts = ['<!doctype html><html lang="ja"><head><meta charset="utf-8"><style>' + css + '</style></head><body>'];
  parts.push('<h1>みまもり状況</h1>');
  parts.push('<div class="sub">最終更新 ' + Utilities.formatDate(new Date(), tz, 'M月d日 HH:mm') + '　／　このページは自動で最新になります（再読み込みしてください）</div>');

  if (dates.length === 0) {
    parts.push('<div class="day">まだ記録がありません。アプリの設定画面で「接続テスト」を試してみてください。</div>');
  }

  dates.forEach(function (d) {
    var rows = byDate[d].sort(function (a, b) { return a.scheduled < b.scheduled ? -1 : 1; });
    var doneCount = rows.filter(function (r) { return r.status === '完了'; }).length;
    var total = rows.filter(function (r) { return r.status !== 'テスト'; }).length;

    parts.push('<div class="day">');
    parts.push('<div class="dayhead"><span class="date">' + escapeHtml_(d) + '</span>' +
      '<span class="count">' + doneCount + ' / ' + total + ' 件 完了</span></div>');

    rows.forEach(function (r) {
      var cls = r.status === '完了' ? 'ok' : (r.status === '未完了' ? 'ng' : 'other');
      parts.push('<div class="row">' +
        '<span class="time">' + escapeHtml_(r.scheduled) + '</span>' +
        '<span class="title">' + escapeHtml_(r.title) + '</span>' +
        '<span class="badge ' + cls + '">' + escapeHtml_(r.status) + '</span>' +
        '</div>');
    });
    parts.push('</div>');
  });

  parts.push('</body></html>');
  return parts.join('');
}

function escapeHtml_(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
