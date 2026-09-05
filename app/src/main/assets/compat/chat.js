window.__tellevGetChatMessages = function(chat, range, options) {
  const substituteParamsExtended = String, system_message_types = { NARRATOR: "narrator" };
  const klona = (value) => JSON.parse(JSON.stringify(value));
  function string_to_range(input, min, max) {
    let start, end;
    const clamp = (value) => _.clamp(value < 0 ? max + value + 1 : value, min, max);
    if (input.match(/^(-?\d+)$/)) {
      start = end = clamp(Number(input));
    } else {
      const match = input.match(/^(-?\d+)-(-?\d+)$/);
      if (!match) {
        return null;
      }
      [start, end] = _.sortBy([match[1], match[2]].map(Number).map(clamp));
    }
    if (isNaN(start) || isNaN(end)) {
      return null;
    }
    return { start, end };
  }
  function getChatMessages(range2, { role = "all", hide_state = "all", include_swipes = false } = {}) {
    const range_demacroed = substituteParamsExtended(range2.toString());
    const range_number = string_to_range(range_demacroed, 0, chat.length - 1);
    if (!range_number) {
      return [];
    }
    const { start, end } = range_number;
    const get_role = (chat_message) => {
      const is_narrator = chat_message.extra?.type === system_message_types.NARRATOR;
      if (is_narrator) {
        if (chat_message.is_user) {
          return "unknown";
        }
        return "system";
      }
      if (chat_message.is_user) {
        return "user";
      }
      return "assistant";
    };
    const process_message = (message_id) => {
      const message = chat[message_id];
      if (!message) {
        return null;
      }
      const message_role = get_role(message);
      if (role !== "all" && message_role !== role) {
        return null;
      }
      if (hide_state !== "all" && hide_state === "hidden" !== message.is_system) {
        return null;
      }
      const swipe_id = message?.swipe_id ?? 0;
      let swipes = message?.swipes ?? [message.mes];
      let swipes_data = message?.variables ?? [{}];
      let swipes_info = message?.swipe_info ?? [message?.extra ?? {}];
      const swipe_length = swipes.length;
      swipes = _.range(0, swipe_length).map((i) => swipes[i] ?? "");
      swipes_data = _.range(0, swipe_length).map((i) => swipes_data[i] ?? {});
      swipes_info = _.range(0, swipe_length).map((i) => swipes_info[i] ?? {});
      const extra = swipes_info[swipe_id];
      const data = swipes_data[swipe_id];
      if (include_swipes) {
        return {
          message_id,
          name: message.name,
          role: message_role,
          is_hidden: message.is_system,
          swipe_id,
          swipes,
          swipes_data,
          swipes_info
        };
      }
      return {
        message_id,
        name: message.name,
        role: message_role,
        is_hidden: message.is_system,
        message: message.mes ?? "",
        data,
        extra,
        // for compatibility
        swipe_id,
        swipes,
        swipes_data
      };
    };
    const chat_messages = _.range(start, end + 1).map((i) => process_message(i)).filter((chat_message) => chat_message !== null);
    return klona(chat_messages);
  }
  return getChatMessages(range ?? "0-" + (chat.length - 1), options);
};
