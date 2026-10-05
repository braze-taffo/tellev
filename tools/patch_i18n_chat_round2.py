#!/usr/bin/env python3
"""Round-2 chat UI i18n keys.

The generated resources keep the hand-maintained grouping, so keys are inserted
next to their siblings here instead of re-running i18n_consolidate.py (its TSV
gate fails on pre-existing drift). Module TSVs are updated to stay in sync.
"""
from pathlib import Path

RES = Path('app/src/main/res')
I18N = Path('_i18n')
SRC = Path('app/src/main/java/app/tellev/core/i18n')

zh = {
    # 顶栏 / 抽屉 / 面板
    'chat_drawer_open': '打开会话抽屉',
    'chat_quick_settings': '设定',
    'chat_panel_world_section': '世界书',
    'chat_panel_world_session': '世界书（本会话）',
    'chat_panel_preset_section': '生成预设',
    'chat_panel_persona_section': '用户设定',
    'chat_panel_world_unbound': '未绑定世界书',
    'chat_panel_persona_default': '默认用户',
    'chat_drawer_new_session': '新会话',
    'chat_drawer_plugins': '插件',
    'chat_drawer_export_log': '导出会话日志',
    'chat_drawer_settings': '设置',
    'chat_drawer_search_sessions': '搜索会话',
    'chat_drawer_no_sessions': '暂无会话',
    'chat_drawer_expand': '展开其余 %1$d 个会话',
    'chat_drawer_session_count': '%1$d 个会话',
    'chat_settings_model': '模型',
    'chat_settings_tts': '自定义 TTS',
    'chat_settings_imagegen': '自定义生图',
    'chat_settings_usage': '使用统计',
    # 消息操作行
    'chat_continue': '继续生成',
    'chat_speak_current': '播放语音',
    'chat_input_voice': '语音输入',
    'chat_input_voice_listening': '正在聆听…',
    'chat_attach_menu_title': '添加附件',
    'chat_attach_audio': '音频',
    'chat_attach_video': '视频',
    'chat_attach_image': '图片',
    'chat_attach_document': '文档',
    'chat_attach_failed': '附件添加失败：%1$s',
    'chat_voice_failed': '语音识别失败：%1$s',
    'chat_voice_unsupported': '当前设备不支持语音识别',
    'chat_input_placeholder': '发消息或创建任务，/ 调用指令，@ 文件或对话',
    'chat_stats_round': '%1$d 轮',
    'chat_stats_step': '%1$d 步',
    'chat_stats_tokens': '%1$s tok',
    'chat_stats_cache_hit': '缓存命中 %1$s',
    'chat_export_log_saved': '会话日志已导出',
    'chat_export_log_failed': '导出失败：%1$s',
    'chat_continue_empty': '还没有可继续的回复',
}

en = {
    'chat_drawer_open': 'Open session drawer',
    'chat_quick_settings': 'Settings',
    'chat_panel_world_section': 'World Books',
    'chat_panel_world_session': 'World Books (this chat)',
    'chat_panel_preset_section': 'Generation Presets',
    'chat_panel_persona_section': 'User Persona',
    'chat_panel_world_unbound': 'No world book bound',
    'chat_panel_persona_default': 'Default user',
    'chat_drawer_new_session': 'New chat',
    'chat_drawer_plugins': 'Plugins',
    'chat_drawer_export_log': 'Export chat log',
    'chat_drawer_settings': 'Settings',
    'chat_drawer_search_sessions': 'Search chats',
    'chat_drawer_no_sessions': 'No chats yet',
    'chat_drawer_expand': 'Show %1$d more chats',
    'chat_drawer_session_count': '%1$d chats',
    'chat_settings_model': 'Model',
    'chat_settings_tts': 'Custom TTS',
    'chat_settings_imagegen': 'Custom image generation',
    'chat_settings_usage': 'Usage stats',
    'chat_continue': 'Continue',
    'chat_speak_current': 'Play audio',
    'chat_input_voice': 'Voice input',
    'chat_input_voice_listening': 'Listening…',
    'chat_attach_menu_title': 'Add attachment',
    'chat_attach_audio': 'Audio',
    'chat_attach_video': 'Video',
    'chat_attach_image': 'Image',
    'chat_attach_document': 'Document',
    'chat_attach_failed': 'Failed to add attachment: %1$s',
    'chat_voice_failed': 'Voice recognition failed: %1$s',
    'chat_voice_unsupported': 'Voice recognition is not available on this device',
    'chat_input_placeholder': 'Message or task, / command, @ file or chat',
    'chat_stats_round': '%1$d round(s)',
    'chat_stats_step': '%1$d step(s)',
    'chat_stats_tokens': '%1$s tok',
    'chat_stats_cache_hit': 'Cache hit %1$s',
    'chat_export_log_saved': 'Chat log exported',
    'chat_export_log_failed': 'Export failed: %1$s',
    'chat_continue_empty': 'No reply to continue yet',
}

ja = {
    'chat_drawer_open': 'セッションドロワーを開く',
    'chat_quick_settings': '設定',
    'chat_panel_world_section': 'ワールドブック',
    'chat_panel_world_session': 'ワールドブック（このチャット）',
    'chat_panel_preset_section': '生成プリセット',
    'chat_panel_persona_section': 'ユーザー設定',
    'chat_panel_world_unbound': 'ワールドブック未紐付け',
    'chat_panel_persona_default': 'デフォルトユーザー',
    'chat_drawer_new_session': '新規チャット',
    'chat_drawer_plugins': 'プラグイン',
    'chat_drawer_export_log': 'チャットログを書き出す',
    'chat_drawer_settings': '設定',
    'chat_drawer_search_sessions': 'チャットを検索',
    'chat_drawer_no_sessions': 'チャットはまだありません',
    'chat_drawer_expand': '他の %1$d 件のチャットを表示',
    'chat_drawer_session_count': '%1$d 件のチャット',
    'chat_settings_model': 'モデル',
    'chat_settings_tts': 'カスタム TTS',
    'chat_settings_imagegen': 'カスタム画像生成',
    'chat_settings_usage': '使用統計',
    'chat_continue': '続けて生成',
    'chat_speak_current': '音声を再生',
    'chat_input_voice': '音声入力',
    'chat_input_voice_listening': '聞き取り中…',
    'chat_attach_menu_title': '添付を追加',
    'chat_attach_audio': '音声',
    'chat_attach_video': '動画',
    'chat_attach_image': '画像',
    'chat_attach_document': 'ドキュメント',
    'chat_attach_failed': '添付の追加に失敗しました：%1$s',
    'chat_voice_failed': '音声認識に失敗しました：%1$s',
    'chat_voice_unsupported': 'この端末では音声認識を利用できません',
    'chat_input_placeholder': 'メッセージやタスク、/ コマンド、@ ファイルやチャット',
    'chat_stats_round': '%1$d ラウンド',
    'chat_stats_step': '%1$d ステップ',
    'chat_stats_tokens': '%1$s tok',
    'chat_stats_cache_hit': 'キャッシュ命中 %1$s',
    'chat_export_log_saved': 'チャットログを書き出しました',
    'chat_export_log_failed': '書き出しに失敗しました：%1$s',
    'chat_continue_empty': '続ける返信がありません',
}

ko = {
    'chat_drawer_open': '세션 서랍 열기',
    'chat_quick_settings': '설정',
    'chat_panel_world_section': '월드북',
    'chat_panel_world_session': '월드북(이 대화)',
    'chat_panel_preset_section': '생성 프리셋',
    'chat_panel_persona_section': '사용자 설정',
    'chat_panel_world_unbound': '월드북 미연결',
    'chat_panel_persona_default': '기본 사용자',
    'chat_drawer_new_session': '새 대화',
    'chat_drawer_plugins': '플러그인',
    'chat_drawer_export_log': '대화 로그 내보내기',
    'chat_drawer_settings': '설정',
    'chat_drawer_search_sessions': '대화 검색',
    'chat_drawer_no_sessions': '대화가 없습니다',
    'chat_drawer_expand': '다른 대화 %1$d개 더 보기',
    'chat_drawer_session_count': '%1$d개 대화',
    'chat_settings_model': '모델',
    'chat_settings_tts': '커스텀 TTS',
    'chat_settings_imagegen': '커스텀 이미지 생성',
    'chat_settings_usage': '사용 통계',
    'chat_continue': '이어서 생성',
    'chat_speak_current': '음성 재생',
    'chat_input_voice': '음성 입력',
    'chat_input_voice_listening': '듣는 중…',
    'chat_attach_menu_title': '첨부 추가',
    'chat_attach_audio': '오디오',
    'chat_attach_video': '비디오',
    'chat_attach_image': '이미지',
    'chat_attach_document': '문서',
    'chat_attach_failed': '첨부 추가 실패: %1$s',
    'chat_voice_failed': '음성 인식 실패: %1$s',
    'chat_voice_unsupported': '이 기기에서는 음성 인식을 사용할 수 없습니다',
    'chat_input_placeholder': '메시지 또는 작업, / 명령, @ 파일 또는 대화',
    'chat_stats_round': '%1$d 라운드',
    'chat_stats_step': '%1$d 스텝',
    'chat_stats_tokens': '%1$s tok',
    'chat_stats_cache_hit': '캐시 적중 %1$s',
    'chat_export_log_saved': '대화 로그를 내보냈습니다',
    'chat_export_log_failed': '내보내기 실패: %1$s',
    'chat_continue_empty': '이어갈 답장이 없습니다',
}

locales = {'values': zh, 'values-en': en, 'values-ja': ja, 'values-ko': ko}


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
    anchor = '    <string name="chat_world_books">%s</string>\n' % table.get('chat_world_books', {
        'values': '世界书', 'values-en': 'World Books', 'values-ja': 'ワールドブック', 'values-ko': '월드북',
    }[dirname])
    assert anchor in t, (dirname, 'chat_world_books anchor')
    for k, v in table.items():
        t = insert_after(t, anchor, k, '    <string name="%s">%s</string>\n' % (k, esc(v)))
    p.write_text(t, encoding='utf-8')
    print('patched', p)

# --- module TSVs (zh source of truth) ---
p = I18N / 'chat_ui.tsv'
t = p.read_text(encoding='utf-8')
rows = ''.join('%s\t%s\n' % (k, v) for k, v in zh.items())
if 'chat_drawer_open' not in t:
    t = t.rstrip('\n') + '\n' + rows
    # 占位符跟随参考图；旧 chat_input_hint 仍被其它入口复用，这里用新键替换输入栏文案。
    p.write_text(t, encoding='utf-8')
    print('patched chat_ui.tsv')

# --- translations.tsv ---
p = I18N / 'translations.tsv'
t = p.read_text(encoding='utf-8')
rows = ''.join('%s\t%s\t%s\t%s\n' % (k, en[k], ja[k], ko[k]) for k in zh)
if 'chat_drawer_open' not in t:
    t = t.rstrip('\n') + '\n' + rows
    p.write_text(t, encoding='utf-8')
    print('patched translations.tsv')

# --- S.kt ---
p = SRC / 'S.kt'
t = p.read_text(encoding='utf-8')
if 'chat_drawer_open' not in t:
    anchor = '    const val chat_world_books = "chat_world_books"\n'
    assert anchor in t
    t = t.replace(anchor, anchor + ''.join('    const val %s = "%s"\n' % (k, k) for k in zh))
    anchor = '        "chat_world_books" to R.string.chat_world_books,\n'
    assert anchor in t
    t = t.replace(anchor, anchor + ''.join('        "%s" to R.string.%s,\n' % (k, k) for k in zh))
    anchor = '    val fallbackZh: Map<String, String> = mapOf(\n'
    assert anchor in t
    fallback = []
    for k, v in zh.items():
        escaped = v.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
        fallback.append('        "%s" to "%s",' % (k, escaped))
    t = t.replace(anchor, anchor + '\n'.join(fallback) + '\n')
    p.write_text(t, encoding='utf-8')
    print('patched S.kt')
