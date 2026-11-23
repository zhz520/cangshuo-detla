// Generated from contracts/openapi.baseline.json. Do not edit by hand.
// Regenerate with: python scripts/generate_openapi_types.py

export interface AcceptRequest {
  captchaId?: string
  captchaCode?: string
  deviceId?: string
}

export interface AccountPassword {
  currentPassword?: string
  password: string
  confirmation: string
}

export interface ActivateRequest {
  themeId: string
}

export interface Admin {
  createdAt?: string
  updatedAt?: string
  id?: number
  username?: string
  password?: string
  nickname?: string
  avatar?: string
  role?: string
  phone?: string
  status?: number
  loginFailCount?: number
  lockTime?: string
  lastLoginAt?: string
  lastLoginIp?: string
  deleted?: number
}

export interface AdminLoginRequest {
  username?: string
  password?: string
  role?: string
  captchaId?: string
  captchaCode?: string
}

export interface AdminLoginResponse {
  token?: string
  adminId?: number
  nickname?: string
  avatar?: string
  role?: string
}

export interface AdminPasswordChangeRequest {
  oldPassword?: string
  newPassword?: string
  confirmation?: string
}

export interface AppProductDetailVO {
  product?: Product
  categoryName?: string
}

export interface Authorization {
  state?: string
  authorizationUrl?: string
  expiresAt?: string
}

export interface AutoVerifyView {
  matched?: boolean
  status?: string
  message?: string
}

export interface Availability {
  ready?: boolean
  reason?: string
}

export interface Banner {
  createdAt?: string
  updatedAt?: string
  id?: number
  imageUrl?: string
  title?: string
  subtitle?: string
  buttonText?: string
  linkType?: string
  linkValue?: string
  sortOrder?: number
  status?: number
  startTime?: string
  endTime?: string
  deleted?: number
}

export interface BatchCategoryRequest {
  ids?: number[]
  categoryId?: number
}

export interface BatchIdsRequest {
  ids?: number[]
}

export interface BatchLevelRequest {
  ids?: number[]
  levelId?: number
}

export interface BatchPriceRequest {
  ids?: number[]
  mode?: string
  value?: number
}

export interface BatchRejectRequest {
  ids?: number[]
  reason?: string
}

export interface BatchResult {
  success?: number
  failed?: number
  failedIds?: number[]
  messages?: string[]
}

export interface BatchRuleRequest {
  matchType?: string
  targetIds?: number[]
  assigneeType?: string
  assigneeId?: number
  enabled?: number
  remark?: string
}

export interface BatchStatusRequest {
  ids?: number[]
  status?: number
}

export interface BindPhone {
  ticket: string
  phone: string
  requestId: string
  code: string
}

export interface Candidate {
  id?: number
  nickname?: string
  avatar?: string
  avgRating?: number
  completedOrders?: number
  activeOrders?: number
  levelId?: number
  levelName?: string
}

export interface Candidates {
  players?: PageCandidate
  maxConcurrent?: number
  requiredLevelId?: number
  requiredLevelName?: string
}

export interface Category {
  createdAt?: string
  updatedAt?: string
  id?: number
  name?: string
  icon?: string
  sortOrder?: number
  status?: number
  parentId?: number
  iconSize?: number
  deleted?: number
  children?: Category[]
}

export interface CategoryFormField {
  id?: number
  categoryId?: number
  fieldKey?: string
  fieldLabel?: string
  fieldType?: string
  options?: string
  placeholder?: string
  required?: number
  sortOrder?: number
  createdAt?: string
}

export interface ChatMessage {
  id?: number
  sessionId?: number
  senderType?: string
  senderId?: number
  type?: string
  content?: string
  isRead?: number
  createdAt?: string
}

export interface ChatSession {
  createdAt?: string
  updatedAt?: string
  id?: number
  id1?: number
  id2?: number
  status?: string
  lastMessageAt?: string
  rating?: number
  ratingComment?: string
  ratedAt?: string
  targetId?: number
  targetName?: string
  avatar?: string
  lastMessage?: string
  unreadCount?: number
  targetType?: string
}

export interface CheckoutRequest {
  moduleId?: string
}

export interface Comparison {
  current?: Period
  previous?: Period
  changePercent?: Record<string, number>
  basis?: string
}

export interface Complaint {
  createdAt?: string
  updatedAt?: string
  id?: number
  orderId?: number
  userId?: number
  playerId?: number
  type?: string
  content?: string
  images?: string
  expectedResult?: string
  status?: string
  result?: string
  refundAmount?: number
  resultReason?: string
  playerPenalty?: string
  operatorId?: number
  resolvedAt?: string
  appealReason?: string
  appealImages?: string
  appealResult?: string
  appealOperatorId?: number
  appealResolvedAt?: string
  csReadAt?: string
  attachmentIds?: string[]
}

export interface ComplaintAttachmentView {
  token?: string
  kind?: string
}

export interface ComplaintDetailVO {
  complaint?: Complaint
  order?: Order
  userNickname?: string
  userAvatar?: string
  playerNickname?: string
  playerAvatar?: string
  progress?: OrderProgress[]
  attachments?: ComplaintAttachmentView[]
  appealAttachments?: ComplaintAttachmentView[]
}

export interface Completion {
  token?: string
  userId?: number
  nickname?: string
  avatar?: string
  phoneBound?: boolean
  bindTicket?: string
  confirmationTicket?: string
}

export interface ContentConfig {
  createdAt?: string
  updatedAt?: string
  id?: number
  configKey?: string
  configValue?: string
  remark?: string
}

export interface Context {
  playerId?: number
  requiredAmount?: number
  paidAmount?: number
  outstandingAmount?: number
  payments?: PaymentOption[]
  history?: RefundView[]
}

export interface CreateOrderRequest {
  productId: number
  specInfo?: string
  amount: number
  gameAccount?: string
  contact?: string
  remark?: string
  designatedPlayerId?: number
  playerLevelId?: number
  couponId?: number
  extraFields?: Record<string, string>
}

export interface CustomerRefundView {
  id?: number
  refundNo?: string
  orderId?: number
  orderNo?: string
  amount?: number
  reason?: string
  status?: string
  publicNote?: string
  submittedAt?: string
  completedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface Daily {
  date?: string
  orderCount?: number
  orderAmount?: number
  playerIncome?: number
  commissionIncome?: number
}

export interface Decision {
  approved?: boolean
  note: string
}

export interface DepositAmountRequest {
  amount?: number
}

export interface EnableRequest {
  enabled: boolean
}

export interface Envelope {
  format: string
  payload: string
  signature: string
}

export interface FeatureView {
  id?: string
  name?: string
  description?: string
  paid?: boolean
  priceCents?: number
  termDays?: number
  minVersion?: string
  dependencies?: string[]
  entryPath?: string
  capabilities?: string[]
  status?: string
  entitled?: boolean
  enabled?: boolean
  reason?: string
  startsAt?: string
  expiresAt?: string
  managedByAuthority?: boolean
}

export interface GroupRequest {
  id?: number
  name?: string
  leaderId?: number
  memberIds?: number[]
  enabled?: number
  remark?: string
}

export interface GuestLookupRequest {
  phone: string
  lookupCode: string
}

export interface GuestOrderCreateRequest {
  phone: string
  lookupCode: string
  privacyAccepted: boolean
  order: CreateOrderRequest
}

export interface GuestOrderSession {
  token?: string
  guestUserId?: number
  nickname?: string
  maskedPhone?: string
  orderId?: number
}

export interface H5LoginRequest {
  phone: string
  code: string
  privacyAccepted?: boolean
  requestId: string
  inviteCode?: string
}

export interface IdentitySettings {
  provider?: string
  configured?: boolean
  enabled?: boolean
  otpEnabled?: boolean
  legacyActive?: boolean
}

export interface InvitationBody {
  name: string
  oneDevice?: boolean
  expiresAt?: string
}

export interface Invite {
  inviteePlayerId?: number
  replacedTeammateId?: number
  splitType?: string
  customAmount?: number
  reason?: string
}

export interface JsonNode {
  [key: string]: unknown
}

export interface LayoutRequest {
  layout?: string
}

export interface LevelRequest {
  name: string
  sortOrder: number
  status: number
  grabCaptchaRequired?: number
  priceIncreaseRate?: number
  withdrawDepositAmount?: number
}

export interface LimitRequest {
  enabled?: boolean
  maxAttempts?: number
  windowHours?: number
  lockHours?: number
}

export interface LoginResponse {
  token?: string
  userId?: number
  nickname?: string
  avatar?: string
  passwordSetupRequired?: boolean
  phoneBound?: boolean
  setupToken?: string
}

export interface MarketView {
  overview?: Overview
  features?: FeatureView[]
  authorityConfigured?: boolean
  lastSync?: string
}

export interface Notice {
  createdAt?: string
  updatedAt?: string
  id?: number
  title?: string
  content?: string
  type?: string
  status?: number
  sortOrder?: number
  popupDisplay?: number
  deleted?: number
}

export interface OpenPlatformCompletion {
  state?: string
}

export interface OperationLog {
  id?: number
  module?: string
  operation?: string
  operatorType?: string
  operatorId?: number
  operatorName?: string
  targetType?: string
  targetId?: number
  detail?: string
  ip?: string
  createdAt?: string
}

export interface Order {
  id?: number
  orderNo?: string
  userId?: number
  productId?: number
  productName?: string
  specInfo?: string
  amount?: number
  baseAmount?: number
  priceIncreaseRate?: number
  priceIncreaseAmount?: number
  memberTier?: string
  memberDiscountRate?: number
  memberDiscountAmount?: number
  couponId?: number
  couponName?: string
  couponDiscountAmount?: number
  requestedPlayerLevelId?: number
  playerLevelName?: string
  commissionRate?: number
  gameAccount?: string
  contact?: string
  remark?: string
  extraFields?: string
  requiredPlayerCount?: number
  designatedPlayerId?: number
  playerId?: number
  csId?: number
  status?: string
  payDeadline?: string
  assignTime?: string
  acceptTime?: string
  teammateDeadline?: string
  startTime?: string
  completeTime?: string
  confirmTime?: string
  autoConfirmDeadline?: string
  settled?: number
  settleAmount?: number
  settleTime?: string
  createdAt?: string
  updatedAt?: string
  playerName?: string
  playerAvatar?: string
  userNickname?: string
  userAvatar?: string
  userPhone?: string
  csName?: string
  teammates?: OrderPlayer[]
}

export interface OrderItem {
  column?: string
  asc?: boolean
}

export interface OrderPlayer {
  id?: number
  orderId?: number
  playerId?: number
  role?: string
  status?: string
  splitType?: string
  splitRatio?: number
  splitAmount?: number
  invitedBy?: number
  invitedByType?: string
  replacesParticipantId?: number
  invitedAt?: string
  inviteDeadline?: string
  acceptedAt?: string
  rejectedAt?: string
  settleAmount?: number
  settledAt?: string
  createdAt?: string
  nickname?: string
  avatar?: string
  fromNickname?: string
  fromAvatar?: string
  toNickname?: string
  toAvatar?: string
  orderNo?: string
}

export interface OrderProgress {
  id?: number
  orderId?: number
  type?: string
  operatorType?: string
  operatorId?: number
  fromStatus?: string
  toStatus?: string
  content?: string
  images?: string
  remark?: string
  createdAt?: string
}

export interface OrderQuote {
  productId?: number
  productName?: string
  designatedPlayerId?: number
  playerLevelId?: number
  playerLevelName?: string
  baseAmount?: number
  priceIncreaseRate?: number
  priceIncreaseAmount?: number
  memberTier?: string
  memberDiscountRate?: number
  memberDiscountAmount?: number
  couponId?: number
  couponName?: string
  couponDiscountAmount?: number
  amount?: number
}

export interface OrderRelayRequest {
  id?: number
  orderId?: number
  originalPlayerId?: number
  splitType?: string
  splitAmount?: number
  reason?: string
  status?: string
  newPlayerId?: number
  reviewedBy?: number
  reviewedAt?: string
  rejectReason?: string
  createdAt?: string
  updatedAt?: string
  orderNo?: string
  productName?: string
  orderAmount?: number
  originalPlayerName?: string
  newPlayerName?: string
}

export interface Overview {
  customerId?: string
  instanceId?: string
  version?: string
  channel?: string
  verifierConfigured?: boolean
  licenseStatus?: string
  licenseId?: string
  expiresAt?: string
  purchaseMode?: string
  updateMode?: string
  allowedDomains?: string[]
  managedByAuthority?: boolean
}

export interface PageAdmin {
  records?: Admin[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageAdmin
  searchCount?: PageAdmin
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageBanner {
  records?: Banner[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageBanner
  searchCount?: PageBanner
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageCandidate {
  records?: Candidate[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageCandidate
  searchCount?: PageCandidate
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageChatMessage {
  records?: ChatMessage[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageChatMessage
  searchCount?: PageChatMessage
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageChatSession {
  records?: ChatSession[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageChatSession
  searchCount?: PageChatSession
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageComplaint {
  records?: Complaint[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageComplaint
  searchCount?: PageComplaint
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageMapStringObject {
  records?: Record<string, unknown>[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageMapStringObject
  searchCount?: PageMapStringObject
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageNotice {
  records?: Notice[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageNotice
  searchCount?: PageNotice
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageOperationLog {
  records?: OperationLog[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageOperationLog
  searchCount?: PageOperationLog
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageOrder {
  records?: Order[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageOrder
  searchCount?: PageOrder
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageOrderRelayRequest {
  records?: OrderRelayRequest[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageOrderRelayRequest
  searchCount?: PageOrderRelayRequest
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PagePlayer {
  records?: Player[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PagePlayer
  searchCount?: PagePlayer
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PagePlayerReplaceRequest {
  records?: PlayerReplaceRequest[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PagePlayerReplaceRequest
  searchCount?: PagePlayerReplaceRequest
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageProduct {
  records?: Product[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageProduct
  searchCount?: PageProduct
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PagePublicPlayerVO {
  records?: PublicPlayerVO[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PagePublicPlayerVO
  searchCount?: PagePublicPlayerVO
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageQuery {
  pageNum?: number
  pageSize?: number
}

export interface PageQuickReply {
  records?: QuickReply[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageQuickReply
  searchCount?: PageQuickReply
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageRefundRequest {
  records?: RefundRequest[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageRefundRequest
  searchCount?: PageRefundRequest
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageResult {
  records?: Record<string, unknown>[]
  total?: number
  pageNum?: number
  pageSize?: number
}

export interface PageReview {
  records?: Review[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageReview
  searchCount?: PageReview
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageReviewView {
  records?: ReviewView[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageReviewView
  searchCount?: PageReviewView
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageReward {
  records?: Reward[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageReward
  searchCount?: PageReward
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageSensitiveWord {
  records?: SensitiveWord[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageSensitiveWord
  searchCount?: PageSensitiveWord
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageSystemNotification {
  records?: SystemNotification[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageSystemNotification
  searchCount?: PageSystemNotification
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageTransaction {
  records?: Transaction[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageTransaction
  searchCount?: PageTransaction
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageUser {
  records?: User[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageUser
  searchCount?: PageUser
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PageWithdraw {
  records?: Withdraw[]
  total?: number
  size?: number
  current?: number
  orders?: OrderItem[]
  optimizeCountSql?: PageWithdraw
  searchCount?: PageWithdraw
  optimizeJoinOfCountSql?: boolean
  maxLimit?: number
  countId?: string
  pages?: number
}

export interface PasswordLoginRequest {
  phone: string
  password: string
  captchaId: string
  captchaCode: string
}

export interface PasswordResetRequest {
  phone: string
  requestId: string
  code: string
  password: string
  confirmation: string
}

export interface PasswordSetupRequest {
  setupToken: string
  password: string
  confirmation: string
}

export interface PayRequest {
  amount: number
  requestId: string
  remark?: string
}

export interface Payment {
  createdAt?: string
  updatedAt?: string
  id?: number
  paymentNo?: string
  orderId?: number
  userId?: number
  bizType?: string
  amount?: number
  rechargeBonus?: number
  payMethod?: string
  wxAppId?: string
  status?: string
  wxTransactionId?: string
  wxPrepayId?: string
  refundAmount?: number
  refundNo?: string
  refundReason?: string
  refundTime?: string
  paidAt?: string
}

export interface PaymentOption {
  paymentId?: number
  paymentNo?: string
  amount?: number
  paidAt?: string
  refundStatus?: string
  refundId?: number
  eligible?: boolean
  blockReason?: string
}

export interface PaymentView {
  id?: number
  paymentNo?: string
  amount?: number
  status?: string
  payMethod?: string
  paidAt?: string
  createdAt?: string
  refundReason?: string
  refundTime?: string
}

export interface Period {
  startDate?: string
  endDate?: string
  summary?: Totals
  days?: Daily[]
}

export interface Player {
  createdAt?: string
  updatedAt?: string
  id?: number
  openid?: string
  nickname?: string
  avatar?: string
  realName?: string
  phone?: string
  skillTags?: string
  gameLevel?: string
  level?: number
  grabCaptchaOverride?: number
  priceIncreaseRate?: number
  introduction?: string
  coverImage?: string
  albumImages?: string
  story?: string
  voiceUrl?: string
  voiceDuration?: number
  proofImages?: string
  serviceTypes?: string
  avgRating?: number
  orderCount?: number
  completeRate?: number
  status?: string
  rejectReason?: string
  depositPaymentNo?: string
  depositOverrideAmount?: number
  frozenUntil?: string
  lastOnlineAt?: string
  isOnline?: number
  deleted?: number
  invitationToken?: string
  deviceId?: string
  privacyAccepted?: boolean
  balance?: number
  completedOrders?: number
  activeOrders?: number
  depositMode?: string
  depositRequiredAmount?: number
  depositPaidAmount?: number
  depositOutstandingAmount?: number
  depositStatus?: string
  depositPaymentCount?: number
  depositLastPaidAt?: string
}

export interface PlayerAccount {
  createdAt?: string
  updatedAt?: string
  id?: number
  playerId?: number
  type?: string
  accountName?: string
  accountNo?: string
  qrcodeUrl?: string
  isDefault?: number
  identityMatched?: boolean
  deleted?: number
}

export interface PlayerAdminUpdateRequest {
  nickname?: string
  avatar?: string
  introduction?: string
  coverImage?: string
  albumImages?: string[]
  story?: string
  voiceUrl?: string
  voiceDuration?: number
  level?: number
  grabCaptchaOverride?: number
  priceIncreaseRate?: number
  realName?: string
}

export interface PlayerHomepageRequest {
  nickname?: string
  avatar?: string
  introduction?: string
  coverImage?: string
  albumImages?: string[]
  story?: string
  voiceUrl?: string
  voiceDuration?: number
}

export interface PlayerLevel {
  createdAt?: string
  updatedAt?: string
  id?: number
  name?: string
  sortOrder?: number
  status?: number
  grabCaptchaRequired?: number
  priceIncreaseRate?: number
  withdrawDepositAmount?: number
  deleted?: number
}

export interface PlayerLoginRequest {
  code?: string
}

export interface PlayerLoginResponse {
  token?: string
  playerId?: number
  nickname?: string
  avatar?: string
}

export interface PlayerProfileVO {
  id?: number
  nickname?: string
  avatar?: string
  realName?: string
  phone?: string
  status?: string
  gameLevel?: string
  level?: number
  introduction?: string
  levelName?: string
  coverImage?: string
  albumImages?: string[]
  story?: string
  voiceUrl?: string
  voiceDuration?: number
  avgRating?: number
  orderCount?: number
  completeRate?: number
  balance?: number
  totalIncome?: number
  isOnline?: number
}

export interface PlayerReplaceRequest {
  id?: number
  orderId?: number
  userId?: number
  oldPlayerId?: number
  reason?: string
  status?: string
  operatorId?: number
  operatorRemark?: string
  createdAt?: string
  processedAt?: string
  orderNo?: string
  productName?: string
  userNickname?: string
  playerNickname?: string
}

export interface PreferenceRequest {
  enabled: boolean
  choices?: string[]
}

export interface Product {
  createdAt?: string
  updatedAt?: string
  id?: number
  categoryId?: number
  name?: string
  subtitle?: string
  description?: string
  coverImage?: string
  images?: string
  price?: number
  status?: number
  sortOrder?: number
  salesCount?: number
  isRecommend?: number
  recommendCategoryId?: number
  avgRating?: number
  reviewCount?: number
  commissionRate?: number
  perUserLimitType?: number
  perUserLimitEnabled?: number
  perUserLimitCount?: number
  deleted?: number
}

export interface Progress {
  status?: string
  account?: Record<string, unknown>
  opened?: boolean
}

export interface ProgressRequest {
  content?: string
  images?: string
}

export interface PublicPlayerVO {
  id?: number
  nickname?: string
  avatar?: string
  level?: number
  levelName?: string
  priceIncreaseRate?: number
  introduction?: string
  coverImage?: string
  albumImages?: string[]
  story?: string
  voiceUrl?: string
  voiceDuration?: number
  canOrder?: boolean
  orderUnavailableReason?: string
  skillTags?: string
  gameLevel?: string
  serviceTypes?: string
  avgRating?: number
  orderCount?: number
  completeRate?: number
  isOnline?: number
  followerCount?: number
  following?: boolean
}

export interface QuickReply {
  createdAt?: string
  updatedAt?: string
  id?: number
  category?: string
  content?: string
  sortOrder?: number
  status?: number
  deleted?: number
}

export interface RAdmin {
  code?: number
  msg?: string
  data?: Admin
}

export interface RAdminLoginResponse {
  code?: number
  msg?: string
  data?: AdminLoginResponse
}

export interface RAppProductDetailVO {
  code?: number
  msg?: string
  data?: AppProductDetailVO
}

export interface RAuthorization {
  code?: number
  msg?: string
  data?: Authorization
}

export interface RAutoVerifyView {
  code?: number
  msg?: string
  data?: AutoVerifyView
}

export interface RAvailability {
  code?: number
  msg?: string
  data?: Availability
}

export interface RBatchResult {
  code?: number
  msg?: string
  data?: BatchResult
}

export interface RCandidates {
  code?: number
  msg?: string
  data?: Candidates
}

export interface RChatSession {
  code?: number
  msg?: string
  data?: ChatSession
}

export interface RComparison {
  code?: number
  msg?: string
  data?: Comparison
}

export interface RComplaintDetailVO {
  code?: number
  msg?: string
  data?: ComplaintDetailVO
}

export interface RCompletion {
  code?: number
  msg?: string
  data?: Completion
}

export interface RContentConfig {
  code?: number
  msg?: string
  data?: ContentConfig
}

export interface RContext {
  code?: number
  msg?: string
  data?: Context
}

export interface RCustomerRefundView {
  code?: number
  msg?: string
  data?: CustomerRefundView
}

export interface RGuestOrderSession {
  code?: number
  msg?: string
  data?: GuestOrderSession
}

export interface RIdentitySettings {
  code?: number
  msg?: string
  data?: IdentitySettings
}

export interface RInteger {
  code?: number
  msg?: string
  data?: number
}

export interface RJSONObject {
  code?: number
  msg?: string
  data?: Record<string, unknown>
}

export interface RJsonNode {
  code?: number
  msg?: string
  data?: JsonNode
}

export interface RListBanner {
  code?: number
  msg?: string
  data?: Banner[]
}

export interface RListCategory {
  code?: number
  msg?: string
  data?: Category[]
}

export interface RListCategoryFormField {
  code?: number
  msg?: string
  data?: CategoryFormField[]
}

export interface RListContentConfig {
  code?: number
  msg?: string
  data?: ContentConfig[]
}

export interface RListCustomerRefundView {
  code?: number
  msg?: string
  data?: CustomerRefundView[]
}

export interface RListFeatureView {
  code?: number
  msg?: string
  data?: FeatureView[]
}

export interface RListInteger {
  code?: number
  msg?: string
  data?: number[]
}

export interface RListMapStringObject {
  code?: number
  msg?: string
  data?: Record<string, unknown>[]
}

export interface RListNotice {
  code?: number
  msg?: string
  data?: Notice[]
}

export interface RListOrderPlayer {
  code?: number
  msg?: string
  data?: OrderPlayer[]
}

export interface RListOrderProgress {
  code?: number
  msg?: string
  data?: OrderProgress[]
}

export interface RListPaymentView {
  code?: number
  msg?: string
  data?: PaymentView[]
}

export interface RListPlayerAccount {
  code?: number
  msg?: string
  data?: PlayerAccount[]
}

export interface RListPlayerLevel {
  code?: number
  msg?: string
  data?: PlayerLevel[]
}

export interface RListProduct {
  code?: number
  msg?: string
  data?: Product[]
}

export interface RListQuickReply {
  code?: number
  msg?: string
  data?: QuickReply[]
}

export interface RListRechargePackage {
  code?: number
  msg?: string
  data?: RechargePackage[]
}

export interface RListRecommendCategory {
  code?: number
  msg?: string
  data?: RecommendCategory[]
}

export interface RListRefundView {
  code?: number
  msg?: string
  data?: RefundView[]
}

export interface RListRequestView {
  code?: number
  msg?: string
  data?: RequestView[]
}

export interface RListReviewableOrder {
  code?: number
  msg?: string
  data?: ReviewableOrder[]
}

export interface RListStatisticsDaily {
  code?: number
  msg?: string
  data?: StatisticsDaily[]
}

export interface RListString {
  code?: number
  msg?: string
  data?: string[]
}

export interface RListSysConfig {
  code?: number
  msg?: string
  data?: SysConfig[]
}

export interface RListUserGameInfo {
  code?: number
  msg?: string
  data?: UserGameInfo[]
}

export interface RLoginResponse {
  code?: number
  msg?: string
  data?: LoginResponse
}

export interface RLong {
  code?: number
  msg?: string
  data?: number
}

export interface RMapStringBoolean {
  code?: number
  msg?: string
  data?: Record<string, boolean>
}

export interface RMapStringInteger {
  code?: number
  msg?: string
  data?: Record<string, number>
}

export interface RMapStringObject {
  code?: number
  msg?: string
  data?: Record<string, unknown>
}

export interface RMapStringString {
  code?: number
  msg?: string
  data?: Record<string, string>
}

export interface RMarketView {
  code?: number
  msg?: string
  data?: MarketView
}

export interface RObject {
  code?: number
  msg?: string
  data?: unknown
}

export interface ROrder {
  code?: number
  msg?: string
  data?: Order
}

export interface ROrderQuote {
  code?: number
  msg?: string
  data?: OrderQuote
}

export interface ROverview {
  code?: number
  msg?: string
  data?: Overview
}

export interface RPageAdmin {
  code?: number
  msg?: string
  data?: PageAdmin
}

export interface RPageBanner {
  code?: number
  msg?: string
  data?: PageBanner
}

export interface RPageChatMessage {
  code?: number
  msg?: string
  data?: PageChatMessage
}

export interface RPageChatSession {
  code?: number
  msg?: string
  data?: PageChatSession
}

export interface RPageComplaint {
  code?: number
  msg?: string
  data?: PageComplaint
}

export interface RPageMapStringObject {
  code?: number
  msg?: string
  data?: PageMapStringObject
}

export interface RPageNotice {
  code?: number
  msg?: string
  data?: PageNotice
}

export interface RPageOperationLog {
  code?: number
  msg?: string
  data?: PageOperationLog
}

export interface RPageOrder {
  code?: number
  msg?: string
  data?: PageOrder
}

export interface RPageOrderRelayRequest {
  code?: number
  msg?: string
  data?: PageOrderRelayRequest
}

export interface RPagePlayer {
  code?: number
  msg?: string
  data?: PagePlayer
}

export interface RPagePlayerReplaceRequest {
  code?: number
  msg?: string
  data?: PagePlayerReplaceRequest
}

export interface RPageProduct {
  code?: number
  msg?: string
  data?: PageProduct
}

export interface RPagePublicPlayerVO {
  code?: number
  msg?: string
  data?: PagePublicPlayerVO
}

export interface RPageQuickReply {
  code?: number
  msg?: string
  data?: PageQuickReply
}

export interface RPageRefundRequest {
  code?: number
  msg?: string
  data?: PageRefundRequest
}

export interface RPageResult {
  code?: number
  msg?: string
  data?: PageResult
}

export interface RPageReview {
  code?: number
  msg?: string
  data?: PageReview
}

export interface RPageReviewView {
  code?: number
  msg?: string
  data?: PageReviewView
}

export interface RPageReward {
  code?: number
  msg?: string
  data?: PageReward
}

export interface RPageSensitiveWord {
  code?: number
  msg?: string
  data?: PageSensitiveWord
}

export interface RPageSystemNotification {
  code?: number
  msg?: string
  data?: PageSystemNotification
}

export interface RPageTransaction {
  code?: number
  msg?: string
  data?: PageTransaction
}

export interface RPageUser {
  code?: number
  msg?: string
  data?: PageUser
}

export interface RPageWithdraw {
  code?: number
  msg?: string
  data?: PageWithdraw
}

export interface RPayment {
  code?: number
  msg?: string
  data?: Payment
}

export interface RPlayer {
  code?: number
  msg?: string
  data?: Player
}

export interface RPlayerLevel {
  code?: number
  msg?: string
  data?: PlayerLevel
}

export interface RPlayerLoginResponse {
  code?: number
  msg?: string
  data?: PlayerLoginResponse
}

export interface RPlayerProfileVO {
  code?: number
  msg?: string
  data?: PlayerProfileVO
}

export interface RProduct {
  code?: number
  msg?: string
  data?: Product
}

export interface RProgress {
  code?: number
  msg?: string
  data?: Progress
}

export interface RPublicPlayerVO {
  code?: number
  msg?: string
  data?: PublicPlayerVO
}

export interface RRefundRequest {
  code?: number
  msg?: string
  data?: RefundRequest
}

export interface RRefundView {
  code?: number
  msg?: string
  data?: RefundView
}

export interface RReward {
  code?: number
  msg?: string
  data?: Reward
}

export interface RRewardReceipt {
  code?: number
  msg?: string
  data?: RewardReceipt
}

export interface RRules {
  code?: number
  msg?: string
  data?: Rules
}

export interface RSecurityBinding {
  code?: number
  msg?: string
  data?: SecurityBinding
}

export interface RSecurityStatus {
  code?: number
  msg?: string
  data?: SecurityStatus
}

export interface RSettings {
  code?: number
  msg?: string
  data?: Settings
}

export interface RSmsSettings {
  code?: number
  msg?: string
  data?: SmsSettings
}

export interface RSnapshot {
  code?: number
  msg?: string
  data?: Snapshot
}

export interface RStatisticsDaily {
  code?: number
  msg?: string
  data?: StatisticsDaily
}

export interface RStatus {
  code?: number
  msg?: string
  data?: Status
}

export interface RStatusView {
  code?: number
  msg?: string
  data?: StatusView
}

export interface RString {
  code?: number
  msg?: string
  data?: string
}

export interface RSummary {
  code?: number
  msg?: string
  data?: Summary
}

export interface RTaskPage {
  code?: number
  msg?: string
  data?: TaskPage
}

export interface RThemeState {
  code?: number
  msg?: string
  data?: ThemeState
}

export interface RUpdateInfo {
  code?: number
  msg?: string
  data?: UpdateInfo
}

export interface RUser {
  code?: number
  msg?: string
  data?: User
}

export interface RUserProfileVO {
  code?: number
  msg?: string
  data?: UserProfileVO
}

export interface RVoid {
  code?: number
  msg?: string
  data?: unknown
}

export interface RWallet {
  code?: number
  msg?: string
  data?: Wallet
}

export interface RWithdraw {
  code?: number
  msg?: string
  data?: Withdraw
}

export interface Reason {
  reason?: string
}

export interface RechargePackage {
  id?: string
  name?: string
  amount?: number
  bonus?: number
  enabled?: boolean
  sort?: number
}

export interface RecommendCategory {
  id?: number
  code?: string
  name?: string
  sortOrder?: number
}

export interface RefundCreateRequest {
  amount?: number
  reason?: string
  clientRequestId?: string
}

export interface RefundDecisionRequest {
  note?: string
}

export interface RefundRequest {
  id?: number
  refundNo?: string
  orderId?: number
  orderNo?: string
  userId?: number
  paymentId?: number
  paymentMethod?: string
  amount?: number
  reason?: string
  status?: string
  orderStatusBefore?: string
  requesterType?: string
  requesterId?: number
  clientRequestId?: string
  csReviewerId?: number
  csRecommendation?: string
  csNote?: string
  adminId?: number
  adminDecision?: string
  adminNote?: string
  sourceType?: string
  sourceId?: number
  channelRefundNo?: string
  lastError?: string
  submittedAt?: string
  csReviewedAt?: string
  adminDecidedAt?: string
  processingAt?: string
  completedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface RefundReviewRequest {
  recommendation?: string
  note?: string
}

export interface RefundView {
  id?: number
  refundNo?: string
  playerId?: number
  userId?: number
  paymentId?: number
  amount?: number
  reason?: string
  status?: string
  reviewerId?: number
  reviewNote?: string
  reviewedAt?: string
  channelRefundNo?: string
  lastError?: string
  createdAt?: string
  completedAt?: string
}

export interface ReplaceTeammateRequest {
  teammatePlayerId?: number
}

export interface Request {
  kind: string
  note?: string
}

export interface RequestView {
  id?: string
  kind?: string
  status?: string
  note?: string
  review_note?: string
  created_at?: string
  reviewed_at?: string
}

export interface ReversalRequest {
  reason: string
}

export interface Review {
  approved?: boolean
  note?: string
  violation?: boolean
}

export interface ReviewCreateRequest {
  orderId: number
  rating: number
  content: string
  images?: string
}

export interface ReviewRequest {
  approve?: boolean
  note?: string
}

export interface ReviewView {
  id?: number
  playerId?: number
  playerName?: string
  rating?: number
  content?: string
  images?: string
  createdAt?: string
  userNickname?: string
  userAvatar?: string
  productName?: string
}

export interface ReviewableOrder {
  id?: number
  orderNo?: string
  productName?: string
}

export interface Reward {
  id?: number
  rewardNo?: string
  requestId?: string
  userId?: number
  playerId?: number
  orderId?: number
  orderNo?: string
  amount?: number
  fee?: number
  income?: number
  feePercent?: number
  userBalanceBefore?: number
  userBalanceAfter?: number
  playerBalanceBefore?: number
  playerBalanceAfter?: number
  reversalUserBalanceBefore?: number
  reversalUserBalanceAfter?: number
  reversalPlayerBalanceBefore?: number
  reversalPlayerBalanceAfter?: number
  paymentMethod?: string
  remark?: string
  status?: string
  pendingRecovery?: number
  reversalReason?: string
  reversalAdminId?: number
  reversedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface RewardReceipt {
  id?: number
  orderId?: number
  amount?: number
  fee?: number
  income?: number
  status?: string
  createdAt?: string
}

export interface RoleBody {
  code: string
  name: string
  permissions: string[]
  enabled?: boolean
}

export interface RuleRequest {
  id?: number
  matchType?: string
  targetId?: number
  assigneeType?: string
  assigneeId?: number
  enabled?: number
  remark?: string
}

export interface Rules {
  rewardEnabled: boolean
  minAmount: number
  maxAmount: number
  validDays: number
  userDailyLimit: number
  playerDailyLimit: number
  pairDailyLimit: number
  feePercent: number
  captchaSeconds: number
  captchaAttempts: number
  captchaRefreshSeconds: number
}

export interface SaveDynamicRequest {
  categoryId?: number
  savedFields?: Record<string, string>
  label?: string
}

export interface SaveGameInfoRequest {
  gameAccount?: string
  contact?: string
  remark?: string
}

export interface SecurityBinding {
  bindTicket?: string
  maskedPhone?: string
}

export interface SecurityStatus {
  phone?: string
  phoneBound?: boolean
  wechatBound?: boolean
  wechatAvailable?: boolean
  passwordSet?: boolean
  passwordLoginEnabled?: boolean
}

export interface SendCode {
  ticket: string
  phone: string
  captchaId: string
  captchaCode: string
}

export interface SensitiveWord {
  id?: number
  word?: string
  category?: string
  status?: number
  createdAt?: string
}

export interface Settings {
  enabled?: boolean
  maxAttempts?: number
  windowHours?: number
  lockHours?: number
}

export interface SettingsRequest {
  provider?: string
  enabled?: boolean
  otpEnabled?: boolean
  endpoint?: string
  accessKeyId?: string
  accessKeySecret?: string
  signName?: string
  verificationTemplateCode?: string
  playerFinishTemplateCode?: string
  csMessageTemplateCode?: string
  teammateInvitedTemplateCode?: string
}

export interface SmsReminderRequest {
  sessionId?: number
  reminderCode?: string
}

export interface SmsSettings {
  provider?: string
  configured?: boolean
  enabled?: boolean
  otpEnabled?: boolean
  legacyActive?: boolean
  endpoint?: string
  accessKeyId?: string
  hasAccessKeySecret?: boolean
  signName?: string
  verificationTemplateCode?: string
  playerFinishTemplateCode?: string
  csMessageTemplateCode?: string
  teammateInvitedTemplateCode?: string
  authorityConfigured?: boolean
}

export interface Snapshot {
  version?: string
  buildId?: string
}

export interface StatisticsDaily {
  id?: number
  statDate?: string
  newUserCount?: number
  activeUserCount?: number
  newOrderCount?: number
  completedOrderCount?: number
  cancelledOrderCount?: number
  totalAmount?: number
  platformIncome?: number
  playerIncome?: number
  newPlayerCount?: number
  activePlayerCount?: number
  newComplaintCount?: number
  avgOrderAmount?: number
  createdAt?: string
}

export interface Status {
  mode?: string
  requiredAmount?: number
  paidAmount?: number
  outstandingAmount?: number
  needsPayment?: boolean
}

export interface StatusView {
  available?: boolean
  reason?: string
  status?: string
  realName?: string
  idNumberMasked?: string
  reviewNote?: string
  submittedAt?: string
  reviewedAt?: string
  hasFrontImage?: boolean
  hasBackImage?: boolean
  autoAvailable?: boolean
  autoReason?: string
}

export interface SubmitRequest {
  realName: string
  idNumber: string
  privacyAccepted?: boolean
}

export interface Summary {
  playerId?: number
  userId?: number
  mode?: string
  requiredAmount?: number
  paidAmount?: number
  outstandingAmount?: number
  status?: string
  paymentCount?: number
  lastPaidAt?: string
  overrideAmount?: number
}

export interface SysConfig {
  createdAt?: string
  updatedAt?: string
  id?: number
  configKey?: string
  configName?: string
  configValue?: string
  valueType?: string
  configGroup?: string
  remark?: string
}

export interface SystemNotification {
  id?: number
  receiverType?: string
  receiverId?: number
  title?: string
  content?: string
  bizType?: string
  relatedId?: number
  isRead?: number
  createdAt?: string
  type?: string
}

export interface Task {
  kind?: string
  id?: string
  version?: string
  title?: string
  reference?: string
  state?: string
  since?: string
  urgent?: boolean
  unread?: boolean
  acknowledged?: boolean
}

export interface TaskPage {
  tasks?: Task[]
  hasMore?: boolean
}

export interface TaskRef {
  kind?: string
  id?: string
  version?: string
}

export interface TestRequest {
  recipients?: unknown
}

export interface ThemeOption {
  id?: string
  name?: string
  description?: string
  active?: boolean
  entitled?: boolean
  enabled?: boolean
  reason?: string
}

export interface ThemeState {
  activeTheme?: string
  themes?: ThemeOption[]
  userSwitchEnabled?: boolean
  userThemeOptions?: string[]
}

export interface Ticket {
  ticket: string
}

export interface Totals {
  orderCount?: number
  orderAmount?: number
  playerIncome?: number
  commissionIncome?: number
  averageOrderAmount?: number
}

export interface Transaction {
  id?: number
  type?: string
  userType?: string
  userId?: number
  amount?: number
  balanceBefore?: number
  balanceAfter?: number
  relatedOrderId?: number
  relatedPaymentId?: number
  relatedWithdrawId?: number
  remark?: string
  createdAt?: string
}

export interface UpdateInfo {
  version?: string
  channel?: string
  schemaVersion?: number
  releaseNotes?: string[]
  automaticUpdateAvailable?: boolean
  status?: string
  message?: string
  sourceUrl?: string
  components?: UpdateOption[]
}

export interface UpdateOption {
  id?: string
  name?: string
  description?: string
  selected?: boolean
}

export interface UpdateSelectionRequest {
  components?: string[]
}

export interface User {
  createdAt?: string
  updatedAt?: string
  id?: number
  openid?: string
  nickname?: string
  avatar?: string
  phone?: string
  isGuest?: number
  status?: number
  deleted?: number
  balance?: number
  passwordSet?: boolean
}

export interface UserGameInfo {
  id?: number
  userId?: number
  categoryId?: number
  gameAccount?: string
  contact?: string
  remark?: string
  savedFields?: string
  label?: string
  createdAt?: string
}

export interface UserProfileVO {
  id?: number
  nickname?: string
  avatar?: string
  phone?: string
  balance?: number
  unreadMessageCount?: number
}

export interface UserRequest {
  phone: string
  nickname: string
  avatar?: string
  status: number
  password?: string
}

export interface Wallet {
  createdAt?: string
  updatedAt?: string
  id?: number
  userId?: number
  balance?: number
  frozenAmount?: number
}

export interface Withdraw {
  createdAt?: string
  updatedAt?: string
  id?: number
  playerId?: number
  accountId?: number
  accountTypeSnapshot?: string
  accountNameSnapshot?: string
  accountNoSnapshot?: string
  accountQrcodeSnapshot?: string
  amount?: number
  status?: string
  payMethod?: string
  proofImage?: string
  rejectReason?: string
  processedBy?: number
  processedAt?: string
  lastNotifyAt?: string
  playerName?: string
  playerPhone?: string
  accountType?: string
  accountNo?: string
  accountName?: string
  qrcodeUrl?: string
}

export interface WithdrawRequest {
  accountId?: number
  amount?: number
}

export interface WxLoginRequest {
  code?: string
  privacyAccepted?: boolean
  phoneCode?: string
  inviteCode?: string
}
