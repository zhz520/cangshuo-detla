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
  Object.freeze({category: '通知与事件', source: 'message', label: '业务正文摘要', example: '订单支付成功，等待打手接单', note: '短信可承载完整正文；公众号 thing 字段最多 20 个字符，不建议把长正文直接塞入。'}),
  Object.freeze({category: '通知与事件', source: 'status', label: '事件状态', example: '新订单', note: '系统按事件转换后的中文短状态，如“退款已初审”“提现已完成”。'}),
  Object.freeze({category: '通知与事件', source: 'time', label: '通知时间', example: '2026-11-08 20:30:00', note: '通知队列创建时间，便于公众号 time 类型字段直接使用。'}),
  Object.freeze({category: '通知与事件', source: 'eventType', label: '事件编码', example: 'ORDER_ACCEPTED', note: '系统内部事件编码，适合排查问题或配置固定说明。'}),

  Object.freeze({category: '客户信息', source: 'customerId', label: '客户 ID', example: '7', note: '订单下单客户的内部 ID。'}),
  Object.freeze({category: '客户信息', source: 'customerName', label: '客户昵称', example: '阿岚', note: '完整昵称；没有客户资料时为“-”。'}),
  Object.freeze({category: '客户信息', source: 'customerPhone', label: '客户联系电话', example: '13800138000', note: '完整手机号，内部通知不脱敏；建议只映射给有业务权限的人员。'}),
  Object.freeze({category: '客户信息', source: 'customerType', label: '客户类型', example: '客户', note: '区分正式客户与游客影子账户。'}),
  Object.freeze({category: '客户信息', source: 'customerStatus', label: '客户账号状态', example: '正常', note: '正常或停用。'}),

  Object.freeze({category: '订单信息', source: 'orderId', label: '订单 ID', example: '630', note: '数据库内部订单 ID。'}),
  Object.freeze({category: '订单信息', source: 'orderNo', label: '订单号', example: 'DG202611080001', note: '对客户展示的业务订单号；非订单类事件为“-”。'}),
  Object.freeze({category: '订单信息', source: 'amount', label: '业务金额', example: '68.00', note: '订单事件的订单金额；提现事件自动取提现金额。公众号 amount 字段自动补币种符号。'}),
  Object.freeze({category: '订单信息', source: 'orderAmount', label: '订单金额', example: '68.00', note: '明确取订单金额，不受提现事件影响。'}),
  Object.freeze({category: '订单信息', source: 'baseAmount', label: '商品原价', example: '58.00', note: '下单时的商品原价快照。'}),
  Object.freeze({category: '订单信息', source: 'priceIncreaseAmount', label: '等级加价金额', example: '10.00', note: '下单时因打手等级产生的加价快照。'}),
  Object.freeze({category: '订单信息', source: 'productId', label: '商品 ID', example: '12', note: '商品内部 ID。'}),
  Object.freeze({category: '订单信息', source: 'productName', label: '商品名称', example: '绝密双护', note: '下单时的商品名称快照。'}),
  Object.freeze({category: '订单信息', source: 'productSpec', label: '商品规格', example: '安卓微信区 / 双护', note: '规格快照，JSON 会按文本输出。'}),
  Object.freeze({category: '订单信息', source: 'orderStatus', label: '订单当前状态', example: '已接单', note: '订单表当前状态的中文名称。'}),
  Object.freeze({category: '订单信息', source: 'gameAccount', label: '游戏账号', example: 'demo_001', note: '客户下单填写的游戏账号，属于敏感业务数据。'}),
  Object.freeze({category: '订单信息', source: 'orderContact', label: '订单联系信息', example: '微信号 demo001', note: '客户在本订单填写的联系信息，可能与账号手机号不同。'}),
  Object.freeze({category: '订单信息', source: 'orderRemark', label: '订单备注', example: '晚上八点后开始', note: '客户下单备注。'}),
  Object.freeze({category: '订单信息', source: 'extraFields', label: '动态下单字段', example: '{"区服":"微信"}', note: '动态表单完整内容，JSON 会按文本输出。'}),
  Object.freeze({category: '订单信息', source: 'requiredPlayerCount', label: '所需打手数', example: '2', note: '该订单需要的主打手与队友总数。'}),
  Object.freeze({category: '订单信息', source: 'requestedPlayerLevel', label: '指定打手等级', example: '魔王', note: '客户下单时选择或系统快照的等级名称。'}),
  Object.freeze({category: '订单信息', source: 'orderCreatedAt', label: '下单时间', example: '2026-11-08 20:20:00', note: '订单创建时间。'}),
  Object.freeze({category: '订单信息', source: 'payDeadline', label: '支付截止时间', example: '2026-11-08 20:50:00', note: '订单待支付截止时间。'}),
  Object.freeze({category: '订单信息', source: 'assignTime', label: '派单时间', example: '2026-11-08 20:31:00', note: '订单被指派给打手的时间。'}),
  Object.freeze({category: '订单信息', source: 'grabTime', label: '抢单/接单时间', example: '2026-11-08 20:32:00', note: '打手接受或抢单的时间，对应订单 accept_time。'}),
  Object.freeze({category: '订单信息', source: 'startTime', label: '开始服务时间', example: '2026-11-08 20:35:00', note: '打手开始服务的时间。'}),
  Object.freeze({category: '订单信息', source: 'completeTime', label: '服务完成时间', example: '2026-11-08 22:10:00', note: '打手提交服务完成的时间。'}),
  Object.freeze({category: '订单信息', source: 'confirmTime', label: '订单确认时间', example: '2026-11-08 22:20:00', note: '客户或系统确认订单完成的时间。'}),
  Object.freeze({category: '订单信息', source: 'settleAmount', label: '打手结算金额', example: '54.40', note: '扣除平台抽成后的打手结算金额。'}),
  Object.freeze({category: '订单信息', source: 'settleTime', label: '结算时间', example: '2026-11-08 22:30:00', note: '订单收益结算时间。'}),
  Object.freeze({category: '订单信息', source: 'orderUpdatedAt', label: '订单更新时间', example: '2026-11-08 22:30:00', note: '订单记录最后更新时间。'}),

  Object.freeze({category: '打手信息', source: 'playerId', label: '打手 ID', example: '18', note: '当前主接打手的内部 ID。'}),
  Object.freeze({category: '打手信息', source: 'playerName', label: '打手昵称', example: '林川', note: '当前主接打手昵称；未指派时为“未指派”。'}),
  Object.freeze({category: '打手信息', source: 'playerPhone', label: '打手联系电话', example: '13900139000', note: '完整手机号，内部通知不脱敏。'}),
  Object.freeze({category: '打手信息', source: 'playerLevel', label: '打手等级', example: '魔王', note: '打手当前等级名称。'}),
  Object.freeze({category: '打手信息', source: 'playerStatus', label: '打手账号状态', example: '正常', note: '待审核、正常、已驳回或已冻结。'}),
  Object.freeze({category: '打手信息', source: 'playerOnline', label: '打手在线状态', example: '在线', note: '在线或离线。'}),
  Object.freeze({category: '打手信息', source: 'playerCompletedOrders', label: '打手累计完成单数', example: '126', note: '打手账号累计完成订单数。'}),

  Object.freeze({category: '退款信息', source: 'refundNo', label: '退款单号', example: 'RF202611080001', note: '退款事件自动查询该订单最新退款单。'}),
  Object.freeze({category: '退款信息', source: 'refundAmount', label: '退款金额', example: '68.00', note: '本次退款申请金额。'}),
  Object.freeze({category: '退款信息', source: 'refundReason', label: '退款原因', example: '打手未按约定时间开始', note: '客户或系统提交的退款原因。'}),
  Object.freeze({category: '退款信息', source: 'refundStatus', label: '退款状态', example: '客服已初审', note: '退款单当前状态的中文名称。'}),
  Object.freeze({category: '退款信息', source: 'refundPaymentMethod', label: '原支付方式', example: 'WECHAT_OFFICIAL', note: '退款对应的原支付渠道编码。'}),
  Object.freeze({category: '退款信息', source: 'refundSubmittedAt', label: '退款申请时间', example: '2026-11-08 20:40:00', note: '退款申请提交时间。'}),
  Object.freeze({category: '退款信息', source: 'refundCsReviewedAt', label: '客服初审时间', example: '2026-11-08 20:45:00', note: '客服完成初审的时间。'}),
  Object.freeze({category: '退款信息', source: 'refundAdminDecidedAt', label: '管理员审定时间', example: '2026-11-08 20:50:00', note: '管理员完成终审的时间。'}),
  Object.freeze({category: '退款信息', source: 'refundProcessingAt', label: '退款处理时间', example: '2026-11-08 20:52:00', note: '提交支付渠道处理的时间。'}),
  Object.freeze({category: '退款信息', source: 'refundCompletedAt', label: '退款完成时间', example: '2026-11-08 20:53:00', note: '退款渠道成功完成的时间。'}),

  Object.freeze({category: '投诉信息', source: 'complaintType', label: '投诉类型', example: '服务质量', note: '服务质量、超时、账号问题、欺诈或其他。'}),
  Object.freeze({category: '投诉信息', source: 'complaintContent', label: '投诉内容', example: '服务未按约定完成', note: '客户提交的投诉正文。'}),
  Object.freeze({category: '投诉信息', source: 'complaintExpectedResult', label: '客户期望结果', example: '重新服务', note: '客户提交投诉时选择的期望处理方式。'}),
  Object.freeze({category: '投诉信息', source: 'complaintStatus', label: '投诉当前状态', example: '处理中', note: '投诉单当前状态的中文名称。'}),
  Object.freeze({category: '投诉信息', source: 'complaintResult', label: '仲裁结果', example: '部分退款', note: '客服仲裁结果的中文名称。'}),
  Object.freeze({category: '投诉信息', source: 'complaintRefundAmount', label: '投诉裁定退款额', example: '30.00', note: '部分退款时裁定的退款金额。'}),
  Object.freeze({category: '投诉信息', source: 'complaintResultReason', label: '仲裁理由', example: '服务时长不足，按比例退款', note: '客服填写并保存的仲裁理由。'}),
  Object.freeze({category: '投诉信息', source: 'complaintPlayerPenalty', label: '打手处罚', example: '警告', note: '无、警告、扣减评分或冻结账号。'}),
  Object.freeze({category: '投诉信息', source: 'complaintResolvedAt', label: '投诉处理时间', example: '2026-11-08 21:00:00', note: '客服完成仲裁的时间。'}),
  Object.freeze({category: '投诉信息', source: 'complaintAppealReason', label: '申诉理由', example: '已按客户要求完成服务', note: '打手或客户提交申诉时的说明。'}),
  Object.freeze({category: '投诉信息', source: 'complaintAppealResult', label: '申诉终审结果', example: '驳回投诉', note: '管理员完成申诉终审后的中文结果。'}),

  Object.freeze({category: '提现信息', source: 'withdrawId', label: '提现 ID', example: '158', note: '提现事件自动按提现 ID 查询，不会误查同号码的订单。'}),
  Object.freeze({category: '提现信息', source: 'withdrawAmount', label: '提现金额', example: '120.00', note: '本次提现申请金额。'}),
  Object.freeze({category: '提现信息', source: 'withdrawStatus', label: '提现状态', example: '待处理', note: '待处理、已完成或已驳回。'}),
  Object.freeze({category: '提现信息', source: 'withdrawAccountType', label: '提现账户类型', example: 'ALIPAY', note: '申请时的收款账户类型快照。'}),
  Object.freeze({category: '提现信息', source: 'withdrawAccountName', label: '提现收款人', example: '张三', note: '申请时的收款人姓名快照。'}),
  Object.freeze({category: '提现信息', source: 'withdrawAccountNo', label: '提现收款账号', example: 'demo@example.com', note: '敏感账户快照，仅映射给有财务或审核权限的人员。'}),
  Object.freeze({category: '提现信息', source: 'withdrawMethod', label: '实际打款方式', example: '支付宝转账', note: '管理员完成打款时填写的方式。'}),
  Object.freeze({category: '提现信息', source: 'withdrawRejectReason', label: '提现驳回原因', example: '收款账户实名不一致', note: '提现被驳回时填写的原因。'}),
  Object.freeze({category: '提现信息', source: 'withdrawAppliedAt', label: '提现申请时间', example: '2026-11-08 18:00:00', note: '打手提交提现申请的时间。'}),
  Object.freeze({category: '提现信息', source: 'withdrawProcessedAt', label: '提现处理时间', example: '2026-11-08 18:30:00', note: '管理员完成或驳回提现的时间。'}),
  Object.freeze({category: '提现信息', source: 'withdrawProofUrl', label: '提现打款凭证', example: 'https://.../proof.jpg', note: '管理员上传的打款凭证 URL。'}),

  Object.freeze({category: '接收人信息', source: 'recipientId', label: '接收人 ID', example: '18', note: '当前通知接收人的内部 ID。'}),
  Object.freeze({category: '接收人信息', source: 'recipientType', label: '接收人角色', example: '打手', note: '客户、游客、打手、管理员、客服或系统。'}),
  Object.freeze({category: '接收人信息', source: 'recipientName', label: '接收人名称', example: '林川', note: '当前通知接收人的昵称或员工名称。'}),
  Object.freeze({category: '接收人信息', source: 'recipientPhone', label: '接收人手机号', example: '13900139000', note: '当前通知接收人的完整手机号，内部通知不脱敏。'})
])

const SOURCE_CATEGORY_ORDER = Object.freeze(['通知与事件', '客户信息', '订单信息', '打手信息', '退款信息', '投诉信息', '提现信息', '接收人信息'])

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

function groupedSources() {
  return SOURCE_CATEGORY_ORDER.map(category => ({
    label: category,
    sources: SOURCE_FIELDS.filter(item => item.category === category).map(item => ({ ...item }))
  }))
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
    sourceGroups: groupedSources(),
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
          '右侧数据源按通知、客户、订单、打手、退款、投诉、提现和接收人分组；下拉框可输入名称或字段编码快速筛选。',
          '保存后让接收人关注公众号，并在对应客户端完成公众号绑定；未关注或未绑定不会投递。'
        ]
      : [
          '登录阿里云控制台，进入“短信服务 → 国内消息 → 模板管理”，创建通知类模板。',
          '在模板正文中填写 ${变量名}，例如“订单号${orderNo}，状态${status}，时间${time}”。',
          '提交审核；审核通过后复制“模板 Code”，通常是 SMS_ 开头的字符串，填入 templateCode。',
          '把正文中的变量名复制到 fields 左侧，但去掉 ${ 和 }；右侧填写对应的系统数据源。',
          '右侧数据源按通知、客户、订单、打手、退款、投诉、提现和接收人分组；客户与打手手机号均为完整号码。',
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
