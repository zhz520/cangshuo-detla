const ROLE_META = Object.freeze({
  user: Object.freeze({
    label: '客户',
    description: '普通客户与游客影子账户；建议突出订单、退款、投诉和审核结果。'
  }),
  player: Object.freeze({
    label: '打手',
    description: '接单员；建议突出派单、接单、素材审核和提现结果。'
  }),
  admin: Object.freeze({
    label: '管理员',
    description: '最高权限管理员；建议突出待处理订单、异常退款、投诉和人员审核。'
  }),
  cs: Object.freeze({
    label: '客服',
    description: '上班中的客服；建议突出待派单、服务进度、退款核验和投诉跟进。'
  })
})

const SOURCE_FIELDS = Object.freeze([
  Object.freeze({source: 'message', label: '业务正文摘要', example: '订单支付成功，等待打手接单', note: '短信可承载完整正文；公众号 thing 字段最多 20 个字符，不建议把长正文直接塞入。'}),
  Object.freeze({source: 'status', label: '业务状态', example: '新订单', note: '系统按事件转换后的短状态，如“退款已初审”“主页素材待审核”。'}),
  Object.freeze({source: 'time', label: '通知生成时间', example: '2026-11-08 20:30:00', note: '公众号通知为系统本地业务时间；短信统一为 yyyy-MM-dd HH:mm:ss。'}),
  Object.freeze({source: 'orderNo', label: '订单号', example: 'DG202611080001', note: '非订单类审核事件通常为“-”，不要设置为必填数字字段。'}),
  Object.freeze({source: 'amount', label: '订单金额', example: '￥68.00', note: '公众号金额带币种符号，短信保留数值；没有关联订单时为 ￥0.00。'}),
  Object.freeze({source: 'customerName', label: '客户昵称', example: '阿岚', note: '用于标明是谁下单；没有客户资料时为“-”。'}),
  Object.freeze({source: 'playerName', label: '打手昵称', example: '林川', note: '未指派时显示“未指派”，可用于派单和人员协作通知。'})
])

const WECHAT_FIELD_TYPES = Object.freeze([
  Object.freeze({prefix: 'thing', max: 20, label: '事物/名称/短说明', example: 'thing1'}),
  Object.freeze({prefix: 'character_string', max: 32, label: '编号/订单号/字符串', example: 'character_string2'}),
  Object.freeze({prefix: 'time', max: 64, label: '日期或时间', example: 'time6'}),
  Object.freeze({prefix: 'amount', max: 20, label: '金额', example: 'amount3'}),
  Object.freeze({prefix: 'phone_number', max: 17, label: '电话号码', example: 'phone_number1'}),
  Object.freeze({prefix: 'car_number', max: 8, label: '车牌号码', example: 'car_number1'}),
  Object.freeze({prefix: 'const', max: 20, label: '平台审核过的固定枚举值', example: 'const1'})
])

const EVENTS = Object.freeze([
  Object.freeze({
    key: 'new_order',
    label: '新订单',
    trigger: '订单支付成功或新订单进入处理流程；按指派对象、在线打手广播或后台权限通知相关角色。',
    wechatContent: '{{thing1.DATA}}\n订单号：{{character_string2.DATA}}\n金额：{{amount3.DATA}}\n客户：{{thing4.DATA}}\n打手：{{thing5.DATA}}\n时间：{{time6.DATA}}',
    wechatFields: { thing1: 'status', character_string2: 'orderNo', amount3: 'amount', thing4: 'customerName', thing5: 'playerName', time6: 'time' },
    smsFields: { message: 'message', orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' },
    smsContent: '${message}订单号${orderNo}，客户${customer}，打手${player}，状态${status}，金额${amount}元，时间${time}。'
  }),
  Object.freeze({
    key: 'order_status',
    label: '订单状态',
    trigger: '指派、接单、开始服务、服务完成、客户或后台确认等订单履约变化。',
    wechatContent: '{{thing1.DATA}}\n订单号：{{character_string2.DATA}}\n金额：{{amount3.DATA}}\n客户：{{thing4.DATA}}\n打手：{{thing5.DATA}}\n时间：{{time6.DATA}}',
    wechatFields: { thing1: 'status', character_string2: 'orderNo', amount3: 'amount', thing4: 'customerName', thing5: 'playerName', time6: 'time' },
    smsFields: { message: 'message', orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' },
    smsContent: '${message}订单号${orderNo}，客户${customer}，打手${player}，状态${status}，金额${amount}元，时间${time}。'
  }),
  Object.freeze({
    key: 'refund_status',
    label: '退款与提现',
    trigger: '退款申请、客服初审、管理员终审、退款执行和失败，以及打手提现完成或驳回。',
    wechatContent: '{{thing1.DATA}}\n订单号：{{character_string2.DATA}}\n金额：{{amount3.DATA}}\n客户：{{thing4.DATA}}\n打手：{{thing5.DATA}}\n时间：{{time6.DATA}}',
    wechatFields: { thing1: 'status', character_string2: 'orderNo', amount3: 'amount', thing4: 'customerName', thing5: 'playerName', time6: 'time' },
    smsFields: { message: 'message', orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' },
    smsContent: '${message}订单号${orderNo}，客户${customer}，打手${player}，状态${status}，金额${amount}元，时间${time}。'
  }),
  Object.freeze({
    key: 'complaint_status',
    label: '投诉处理',
    trigger: '新投诉、客户申诉、投诉退款等待终审以及仲裁处理完成。',
    wechatContent: '{{thing1.DATA}}\n订单号：{{character_string2.DATA}}\n金额：{{amount3.DATA}}\n客户：{{thing4.DATA}}\n打手：{{thing5.DATA}}\n时间：{{time6.DATA}}',
    wechatFields: { thing1: 'status', character_string2: 'orderNo', amount3: 'amount', thing4: 'customerName', thing5: 'playerName', time6: 'time' },
    smsFields: { message: 'message', orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' },
    smsContent: '${message}订单号${orderNo}，客户${customer}，打手${player}，状态${status}，金额${amount}元，时间${time}。'
  }),
  Object.freeze({
    key: 'staff_review',
    label: '人员审核',
    trigger: '打手入驻申请、主页素材审核结果、账号冻结和解冻。',
    wechatContent: '{{thing1.DATA}}\n审核时间：{{time2.DATA}}',
    wechatFields: { thing1: 'status', time2: 'time' },
    smsFields: { message: 'message', status: 'status', time: 'time' },
    smsContent: '${message}审核状态：${status}，时间${time}。'
  })
])

const ROLE_SAMPLES = Object.freeze({
  user: Object.freeze({
    new_order: '订单支付成功，等待打手接单。订单号 DG202611080001，商品：绝密双护，金额：¥68.00。',
    order_status: '您的订单已开始服务。订单号 DG202611080001，打手：林川，时间：2026-11-08 20:35:00。',
    refund_status: '退款已初审，正在等待管理员终审。退款单 RF202611080001，订单号 DG202611080001，退款金额：¥68.00。',
    complaint_status: '投诉仲裁已完成：退款申请已进入终审。订单号 DG202611080001，处理时间：2026-11-08 20:45:00。',
    staff_review: '账号资料审核结果：已通过。审核时间：2026-11-08 20:50:00。'
  }),
  player: Object.freeze({
    new_order: '新订单待接单：订单号 DG202611080001，客户：阿岚，商品：绝密双护，金额：¥68.00。',
    order_status: '您有一笔新的指派订单，请确认。订单号 DG202611080001，客户：阿岚，金额：¥68.00。',
    refund_status: '提现已完成，金额 ¥68.00，请查看打款凭证。',
    complaint_status: '订单仲裁结果：需重新提供服务。订单号 DG202611080001，客户：阿岚。',
    staff_review: '主页资料审核通过，已生效。审核时间：2026-11-08 20:50:00。'
  }),
  admin: Object.freeze({
    new_order: '新订单待处理：订单号 DG202611080001，客户：阿岚，打手：未指派，商品：绝密双护，金额：¥68.00。',
    order_status: '订单状态更新：订单号 DG202611080001 已开始服务，客户：阿岚，打手：林川。',
    refund_status: '退款流程更新：投诉退款等待管理员终审（退款单 RF202611080001，订单 DG202611080001）。',
    complaint_status: '投诉处理更新：新投诉待处理（订单 DG202611080001，客户：阿岚）。',
    staff_review: '人员审核更新：新的打手入驻申请待审核（申请人：林川）。'
  }),
  cs: Object.freeze({
    new_order: '新订单待处理：订单号 DG202611080001，客户：阿岚，当前暂无打手在线，请尽快处理派单。',
    order_status: '订单状态更新：订单号 DG202611080001 打手已接单，客户：阿岚，请及时跟进服务进度。',
    refund_status: '退款流程更新：退款申请已提交，请先核验订单和退款原因（退款单 RF202611080001）。',
    complaint_status: '投诉处理更新：新投诉待处理，请及时联系客户并保留处理记录。',
    staff_review: '人员审核更新：打手主页素材待审核，请审核头像、背景图和语音内容。'
  })
})

const ORDER_FIELDS = Object.freeze({
  thing1: 'status',
  character_string2: 'orderNo',
  amount3: 'amount',
  thing4: 'customerName',
  thing5: 'playerName',
  time6: 'time'
})
const REVIEW_FIELDS = Object.freeze({
  thing1: 'status',
  time2: 'time'
})

function wechatExample(roleKey) {
  const role = ROLE_META[roleKey].label
  const template = (event, review = false) => ({
    templateId: `请替换为${role}-${event}-微信模板ID`,
    fields: { ...(review ? REVIEW_FIELDS : ORDER_FIELDS) }
  })
  return {
    new_order: template('新订单'),
    order_status: template('订单状态'),
    refund_status: template('退款状态'),
    complaint_status: template('投诉状态'),
    staff_review: template('人员审核', true)
  }
}

function smsExample(roleKey) {
  const role = ROLE_META[roleKey].label
  const template = (event, review = false) => ({
    templateCode: `请替换为${role}-${event}-阿里云短信模板Code`,
    fields: review
      ? { message: 'message', status: 'status', time: 'time' }
      : { message: 'message', orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' }
  })
  return {
    new_order: template('新订单'),
    order_status: template('订单状态'),
    refund_status: template('退款状态'),
    complaint_status: template('投诉状态'),
    staff_review: template('人员审核', true)
  }
}

function parseConfigKey(configKey) {
  const key = String(configKey || '')
  let match = key.match(/^wechat\.official\.templates\.(user|player|admin|cs)$/)
  if (match) return { channel: 'wechat', roleKey: match[1] }
  match = key.match(/^sms\.notification\.templates\.(user|player|admin|cs)$/)
  if (match) return { channel: 'sms', roleKey: match[1] }
  return null
}

export function notificationConfigExample(configKey) {
  const parsed = parseConfigKey(configKey)
  if (!parsed) return ''
  return JSON.stringify(parsed.channel === 'wechat' ? wechatExample(parsed.roleKey) : smsExample(parsed.roleKey), null, 2)
}

export function notificationConfigGuide(configKey) {
  const parsed = parseConfigKey(configKey)
  if (!parsed) return null
  const role = ROLE_META[parsed.roleKey]
  const channel = parsed.channel
  return {
    ...parsed,
    roleLabel: role.label,
    roleDescription: role.description,
    provider: channel === 'wechat' ? '微信公众号后台' : '阿里云短信控制台',
    sources: SOURCE_FIELDS.map(item => ({ ...item })),
    wechatFieldTypes: channel === 'wechat' ? WECHAT_FIELD_TYPES.map(item => ({ ...item })) : [],
    events: EVENTS.map(item => ({
      key: item.key,
      label: item.label,
      trigger: item.trigger,
      content: channel === 'wechat' ? item.wechatContent : item.smsContent,
      fields: { ...(channel === 'wechat' ? item.wechatFields : item.smsFields) },
      finalExample: ROLE_SAMPLES[parsed.roleKey][item.key]
    })),
    providerSteps: channel === 'wechat'
      ? [
          '仅认证服务号可申请模板消息；进入“功能 → 添加功能插件 → 模板消息”申请权限。',
          '按服务号行业设置类目后，在“公共模板库/我的模板”选用模板；每个服务号最多可同时使用 25 个模板。',
          '打开已选用模板详情，复制“模板 ID”填入当前事件的 templateId。',
          '模板变量必须以 .DATA 结尾。复制字段名前缀作为左侧变量，例如正文 {{thing1.DATA}} 对应 thing1。',
          '新模板优先使用 thing、character_string、time、amount、phone_number、car_number、const 等官方类型；不要继续使用旧版 phrase 字段。',
          '右侧只能填写系统数据源 message、status、time、orderNo、amount、customerName、playerName。',
          '保存后让接收人关注公众号，并在对应客户端完成公众号绑定；未关注或未绑定不会投递。'
        ]
      : [
          '登录阿里云控制台，进入“短信服务 → 国内消息 → 模板管理”，创建通知类模板。',
          '在模板正文中填写 ${变量名}，例如“订单号${orderNo}，状态${status}，时间${time}”。',
          '提交审核；审核通过后复制“模板 Code”，通常是 SMS_ 开头的字符串，填入 templateCode。',
          '把正文中的变量名复制到 fields 左侧，但去掉 ${ 和 }；右侧填写对应的系统数据源。',
          '在“短信签名”和系统“短信配置”中分别维护已审核签名、AccessKey ID 与 AccessKey Secret。',
          '短信模板必须按实际接收人分别配置；手机号为空、模板未审核或变量不匹配时不会正常投递。'
        ],
    linkHelp: channel === 'wechat'
      ? '点击链接不属于模板字段，应填入每个事件的可选“消息点击链接”；后端会按官方发送接口的 url 参数提交。若同时配置小程序 AppID 和页面路径，微信优先跳转小程序。'
      : '阿里云后台的“模板内容”与这里左侧变量名必须完全对应；左侧不要写 ${}，也不要自行改成同义变量。',
    officialDocs: channel === 'wechat'
      ? [
          { label: '官方模板消息说明', url: 'https://developers.weixin.qq.com/doc/service/guide/product/template_message/Template_Message_Interface.html' },
          { label: '官方发送模板消息接口', url: 'https://developers.weixin.qq.com/doc/service/api/notify/template/api_sendtemplatemessage' }
        ]
      : [],
    officialVersion: channel === 'wechat'
      ? '2026-11-08 核对：模板消息说明最后编辑 2026-03-05；发送模板消息接口最后编辑 2025-11-25。'
      : ''
  }
}

export const NOTIFICATION_EVENTS = EVENTS.map(item => item.key)
export const NOTIFICATION_ROLES = Object.keys(ROLE_META)
export const NOTIFICATION_SAMPLES = ROLE_SAMPLES
export const WECHAT_FIELD_LIMITS = WECHAT_FIELD_TYPES
export const NOTIFICATION_SOURCES = SOURCE_FIELDS.map(item => ({ ...item }))
