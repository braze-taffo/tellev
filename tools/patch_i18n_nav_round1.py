#!/usr/bin/env python3
"""Patch i18n generated resources (strings.xml + S.kt) with round-1 nav refactor keys.

The working-tree generated resources are hand-maintaining a layout the global
i18n_consolidate.py cannot reproduce (its TSV gate fails on pre-existing drift),
so new keys are inserted next to their siblings here instead.
"""
from pathlib import Path

RES = Path('app/src/main/res')
SRC = Path('app/src/main/java/app/tellev/core/i18n')

zh = {
    'nav_tab_workshop': '角色工坊',
    'nav_tab_community': '类脑',
    'chat_world_books': '世界书',
    'chars_edit_card': '编辑卡片',
    'ext_entry_title': '扩展功能',
    'ext_entry_hint': '脚本、正则替换、长期记忆与兼容模块都在这里管理',
    'comm_title': '类脑',
    'comm_subtitle': '角色扮演酒馆社区',
    'comm_desc': '类脑（ΟΔΥΣΣΕΙΑ）是中文 SillyTavern 酒馆生态最大的 Discord 社区，分享角色卡、预设、世界书与教程。',
    'comm_main_entry': '社区主入口',
    'comm_main_entry_desc': 'discord.gg/odysseia',
    'comm_guide': '新手入门指南',
    'comm_guide_desc': '如何加入社区、答题与解锁角色卡下载区',
    'comm_hint': '在社区里下载的角色卡文件会自动导入 tellev，无需手动操作。',
    'comm_hint_network': '加入与浏览社区需要可以访问 Discord 的网络环境。',
    'comm_reload': '刷新',
    'comm_open_external': '在 Discord 中打开',
    'comm_fallback_title': '暂时无法打开社区页面',
    'comm_fallback_body': '当前网络无法连接 Discord，请稍后重试，或切换到可访问 Discord 的网络后再打开。',
    'comm_retry': '重试',
    'comm_import_title': '导入下载的角色卡',
    'comm_import_body': '文件 %1$s 已下载，是否导入到 tellev 角色卡库？',
    'comm_import_confirm': '导入',
    'comm_import_success': '角色卡“%1$s”已导入',
    'comm_import_failed': '导入失败：%1$s',
    'comm_unsupported': '该文件不是角色卡，已跳过自动导入：%1$s',
    'comm_external_link': '链接已在外部浏览器中打开',
    'comm_webview_disabled': '当前设备的 WebView 不可用，只能在外部浏览器中打开社区。',
}

en = {
    'nav_tab_workshop': 'Workshop',
    'nav_tab_community': 'Leinao',
    'chat_world_books': 'World Books',
    'chars_edit_card': 'Edit card',
    'ext_entry_title': 'Extensions',
    'ext_entry_hint': 'Scripts, regex replacement, long-term memory and compatibility modules',
    'comm_title': 'Leinao',
    'comm_subtitle': 'Roleplay tavern community',
    'comm_desc': 'Leinao (ΟΔΥΣΣΕΙΑ) is the largest Chinese SillyTavern Discord community, sharing character cards, presets, world books and tutorials.',
    'comm_main_entry': 'Community entrance',
    'comm_main_entry_desc': 'discord.gg/odysseia',
    'comm_guide': 'Getting-started guide',
    'comm_guide_desc': 'How to join, pass the entry quiz and unlock the card download area',
    'comm_hint': 'Character card files downloaded inside the community are imported into tellev automatically.',
    'comm_hint_network': 'Browsing and joining the community requires a network that can reach Discord.',
    'comm_reload': 'Refresh',
    'comm_open_external': 'Open in Discord',
    'comm_fallback_title': 'Cannot open the community page',
    'comm_fallback_body': 'The current network cannot reach Discord. Try again later or switch to a network that can access Discord.',
    'comm_retry': 'Retry',
    'comm_import_title': 'Import downloaded character card',
    'comm_import_body': 'File %1$s has been downloaded. Import it into the tellev card library?',
    'comm_import_confirm': 'Import',
    'comm_import_success': 'Character card "%1$s" imported',
    'comm_import_failed': 'Import failed: %1$s',
    'comm_unsupported': 'Not a character card, automatic import skipped: %1$s',
    'comm_external_link': 'Link opened in the external browser',
    'comm_webview_disabled': 'WebView is unavailable on this device; open the community in an external browser.',
}

ja = {
    'nav_tab_workshop': 'ワークショップ',
    'nav_tab_community': 'Leinao',
    'chat_world_books': 'ワールドブック',
    'chars_edit_card': 'カードを編集',
    'ext_entry_title': '拡張機能',
    'ext_entry_hint': 'スクリプト、正規表現、長期記憶、互換モジュール',
    'comm_title': 'Leinao',
    'comm_subtitle': 'ロールプレイ酒場コミュニティ',
    'comm_desc': '類脳（ΟΔΥΣΣΕΙΑ）は中国語SillyTavern酒場生態最大のDiscordコミュニティで、キャラカード、プリセット、ワールドブック、チュートリアルを共有しています。',
    'comm_main_entry': 'コミュニティ入口',
    'comm_main_entry_desc': 'discord.gg/odysseia',
    'comm_guide': '初心者ガイド',
    'comm_guide_desc': '参加方法、入室クイズ、カードダウンロード解放まで',
    'comm_hint': 'コミュニティ内でダウンロードしたキャラカードファイルは tellev に自動インポートされます。',
    'comm_hint_network': 'コミュニティの閲覧・参加には Discord に接続できるネットワークが必要です。',
    'comm_reload': '再読み込み',
    'comm_open_external': 'Discord で開く',
    'comm_fallback_title': 'コミュニティページを開けません',
    'comm_fallback_body': '現在のネットワークでは Discord に接続できません。しばらくしてから再試行するか、Discord に接続できるネットワークに切り替えてください。',
    'comm_retry': '再試行',
    'comm_import_title': 'ダウンロードしたキャラカードをインポート',
    'comm_import_body': 'ファイル %1$s をダウンロードしました。tellev のカードライブラリにインポートしますか？',
    'comm_import_confirm': 'インポート',
    'comm_import_success': 'キャラカード「%1$s」をインポートしました',
    'comm_import_failed': 'インポートに失敗しました：%1$s',
    'comm_unsupported': 'キャラカードではないため自動インポートをスキップしました：%1$s',
    'comm_external_link': 'リンクを外部ブラウザで開きました',
    'comm_webview_disabled': 'この端末では WebView を利用できないため、外部ブラウザでコミュニティを開いてください。',
}

ko = {
    'nav_tab_workshop': '워크숍',
    'nav_tab_community': 'Leinao',
    'chat_world_books': '월드북',
    'chars_edit_card': '카드 편집',
    'ext_entry_title': '확장',
    'ext_entry_hint': '스크립트, 정규 표현식, 장기 메모리 및 호환성 모듈',
    'comm_title': 'Leinao',
    'comm_subtitle': '롤플레이 선술집 커뮤니티',
    'comm_desc': 'Leinao(ΟΔΥΣΣΕΙΑ)는 중국어 SillyTavern 선술집 생태계 최대 Discord 커뮤니티로, 캐릭터 카드·프리셋·월드북·튜토리얼을 공유합니다.',
    'comm_main_entry': '커뮤니티 입구',
    'comm_main_entry_desc': 'discord.gg/odysseia',
    'comm_guide': '초보자 가이드',
    'comm_guide_desc': '가입 방법, 입장 퀴즈, 카드 다운로드 잠금 해제',
    'comm_hint': '커뮤니티에서 다운로드한 캐릭터 카드 파일은 tellev에 자동으로 가져옵니다.',
    'comm_hint_network': '커뮤니티 이용에는 Discord에 접속 가능한 네트워크가 필요합니다.',
    'comm_reload': '새로고침',
    'comm_open_external': 'Discord에서 열기',
    'comm_fallback_title': '커뮤니티 페이지를 열 수 없음',
    'comm_fallback_body': '현재 네트워크로는 Discord에 연결할 수 없습니다. 잠시 후 다시 시도하거나 Discord 접속이 가능한 네트워크로 전환하세요.',
    'comm_retry': '재시도',
    'comm_import_title': '다운로드한 캐릭터 카드 가져오기',
    'comm_import_body': '파일 %1$s을(를) 다운로드했습니다. tellev 카드 라이브러리로 가져올까요?',
    'comm_import_confirm': '가져오기',
    'comm_import_success': '캐릭터 카드 "%1$s"을(를) 가져왔습니다',
    'comm_import_failed': '가져오기 실패: %1$s',
    'comm_unsupported': '캐릭터 카드가 아니므로 자동 가져오기를 건너뛰었습니다: %1$s',
    'comm_external_link': '링크를 외부 브라우저에서 열었습니다',
    'comm_webview_disabled': '이 기기에서는 WebView를 사용할 수 없어 외부 브라우저로 커뮤니티를 여세요.',
}

locales = {'values': zh, 'values-en': en, 'values-ja': ja, 'values-ko': ko}
sib = {
    'nav_tab_chat': {'values': '聊天', 'values-en': 'Chat', 'values-ja': 'チャット', 'values-ko': '채팅'},
    'nav_tab_settings': {'values': '设置', 'values-en': 'Settings', 'values-ja': '設定', 'values-ko': '설정'},
    'chars_ai_edit': {'values': 'AI 编辑', 'values-en': 'AI Edit', 'values-ja': 'AI編集', 'values-ko': 'AI 편집'},
    'ext_title': {'values': '扩展', 'values-en': 'Extensions', 'values-ja': '拡張', 'values-ko': '확장'},
}
chars_tab = {'values-en': 'Cards', 'values-ja': 'キャラカード', 'values-ko': '캐릭터 카드'}


def esc(s):
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
             .replace("'", "\\'").replace('"', '\\"'))


def insert_after(t, anchor, key, line):
    if ('name="%s"' % key) in t:
        return t
    return t.replace(anchor, anchor + line, 1)


for dirname, table in locales.items():
    p = RES / dirname / 'strings.xml'
    t = p.read_text(encoding='utf-8')

    if dirname == 'values':
        for old_v in ('角色',):
            marker = '<string name="nav_tab_characters">%s</string>' % old_v
            if marker in t:
                t = t.replace(marker, '<string name="nav_tab_characters">角色卡</string>')
    else:
        for old_v in ('Characters', 'キャラクター', '캐릭터', 'Cards', 'キャラカード', '캐릭터 카드'):
            marker = '<string name="nav_tab_characters">%s</string>' % old_v
            if marker in t and old_v not in ('Cards', 'キャラカード', '캐릭터 카드'):
                t = t.replace(marker, '<string name="nav_tab_characters">%s</string>' % chars_tab[dirname])
                break

    anchor = '    <string name="nav_tab_chat">%s</string>\n' % sib['nav_tab_chat'][dirname]
    assert anchor in t, (dirname, 'nav_tab_chat anchor')
    t = insert_after(t, anchor, 'nav_tab_workshop',
                     '    <string name="nav_tab_workshop">%s</string>\n' % esc(table['nav_tab_workshop']))
    t = insert_after(t, anchor, 'nav_tab_community',
                     '    <string name="nav_tab_community">%s</string>\n' % esc(table['nav_tab_community']))

    anchor = '    <string name="nav_tab_settings">%s</string>\n' % sib['nav_tab_settings'][dirname]
    assert anchor in t, (dirname, 'nav_tab_settings anchor')
    t = insert_after(t, anchor, 'chat_world_books',
                     '    <string name="chat_world_books">%s</string>\n' % esc(table['chat_world_books']))

    anchor = '    <string name="chars_ai_edit">%s</string>\n' % sib['chars_ai_edit'][dirname]
    assert anchor in t, (dirname, 'chars_ai_edit anchor')
    t = insert_after(t, anchor, 'chars_edit_card',
                     '    <string name="chars_edit_card">%s</string>\n' % esc(table['chars_edit_card']))

    anchor = '    <string name="ext_title">%s</string>\n' % sib['ext_title'][dirname]
    assert anchor in t, (dirname, 'ext_title anchor')
    t = insert_after(t, anchor, 'ext_entry_title',
                     '    <string name="ext_entry_title">%s</string>\n' % esc(table['ext_entry_title']))
    t = insert_after(t, anchor, 'ext_entry_hint',
                     '    <string name="ext_entry_hint">%s</string>\n' % esc(table['ext_entry_hint']))

    for k, v in table.items():
        if k.startswith('comm_'):
            t = insert_after(t, '</resources>', k, '')
    comm_lines = ''.join('    <string name="%s">%s</string>\n' % (k, esc(v))
                         for k, v in table.items() if k.startswith('comm_')
                         and ('name="%s"' % k) not in t)
    assert '</resources>' in t
    t = t.replace('</resources>', comm_lines + '</resources>')
    p.write_text(t, encoding='utf-8')
    print('patched', p)

# --- S.kt ---
p = SRC / 'S.kt'
t = p.read_text(encoding='utf-8')
if 'nav_tab_workshop' not in t:
    anchor = '    const val nav_tab_chat = "nav_tab_chat"\n'
    assert anchor in t
    t = t.replace(anchor, anchor + '    const val nav_tab_workshop = "nav_tab_workshop"\n'
                  '    const val nav_tab_community = "nav_tab_community"\n')
    anchor = '        "nav_tab_chat" to R.string.nav_tab_chat,\n'
    assert anchor in t
    t = t.replace(anchor, anchor + '        "nav_tab_workshop" to R.string.nav_tab_workshop,\n'
                  '        "nav_tab_community" to R.string.nav_tab_community,\n')
    anchor = '    val fallbackZh: Map<String, String> = mapOf(\n'
    assert anchor in t
    zh_lines = []
    for k, v in zh.items():
        escaped = v.replace('\\', '\\\\').replace('"', '\\"')
        zh_lines.append('        "%s" to "%s",' % (k, escaped))
    t = t.replace(anchor, anchor + '\n'.join(zh_lines) + '\n')
    p.write_text(t, encoding='utf-8')
    print('patched', p)
else:
    print('S.kt already patched, skipped')
