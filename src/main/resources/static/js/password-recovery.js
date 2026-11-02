const empty = () => ({
  step: 'verify', email: '', code: '', password: '', confirmPassword: '',
  showPassword: false, showConfirmation: false, challengeId: '', maskedEmail: '',
  busy: false, restoring: false, restoreFailed: false, error: '', notice: '',
  resendSeconds: 0, blockedSeconds: 0, expiresSeconds: 0,
});

export function createPasswordRecovery({state, api, onComplete, now = Date.now,
  setIntervalFn = setInterval, clearIntervalFn = clearInterval}) {
  Object.assign(state, empty());
  let epoch = 0, controller, disposed = false, timer;
  let resendAt = 0, blockedAt = 0, expiresAt = 0;
  const seconds = value => Math.max(0, Math.ceil((value - now()) / 1000));
  const duration = (value, fallback) => Number.isFinite(Number(value)) && Number(value) > 0 ? Math.min(86400, Math.ceil(Number(value))) : fallback;
  const clearInputs = () => {
    state.code = state.password = state.confirmPassword = '';
    state.showPassword = state.showConfirmation = false;
  };
  function cancel() {
    epoch++;
    controller?.abort(); controller = undefined;
    state.busy = state.restoring = false;
  }
  function dropChallenge() {
    clearInputs(); state.challengeId = state.maskedEmail = ''; state.step = 'verify'; expiresAt = 0;
  }
  function tick() {
    state.resendSeconds = seconds(resendAt);
    state.blockedSeconds = seconds(blockedAt);
    state.expiresSeconds = seconds(expiresAt);
    if (expiresAt && !state.expiresSeconds) {
      const passwordStep = state.step === 'password';
      cancel(); dropChallenge();
      state.notice = '';
      state.error = passwordStep ? '设置密码的时间已过，请重新获取验证码。' : '验证码已过期，请重新获取。';
    }
  }
  function reset() {
    cancel(); resendAt = blockedAt = expiresAt = 0;
    Object.assign(state, empty());
  }
  function editEmail(value) {
    cancel(); dropChallenge(); state.email = value; state.error = state.notice = ''; state.restoreFailed = false; tick();
  }
  function changeEmail() {
    editEmail(state.email);
  }
  function inputCode(value) {
    state.code = String(value).replace(/[^0-9]/g, '').slice(0, 6);
  }
  function start() {
    cancel(); controller = new AbortController();
    state.busy = true; state.error = ''; state.restoreFailed = false;
    return {epoch, signal: controller.signal};
  }
  const current = request => !disposed && request.epoch === epoch && !request.signal.aborted;
  function finish(request) { if (current(request)) state.busy = state.restoring = false; }
  function failed(error, request, action) {
    if (!current(request) || error.name === 'AbortError') return;
    const code = error.code || error.details?.code;
    if (code === 'reset_challenge_mismatch') state.restoreFailed = true;
    if (['reset_challenge_expired', 'reset_challenge_locked', 'reset_grant_expired'].includes(code)) {
      dropChallenge(); state.notice = '';
    }
    if (error.status === 429) {
      const retry = duration(error.details?.retryAfterSeconds, 60);
      blockedAt = now() + retry * 1000;
      if (action === 'send') resendAt = Math.max(resendAt, blockedAt);
      state.error = '操作过于频繁，请等待倒计时结束后重试。'; tick();
    } else state.error = error.message || '请求失败，请稍后重试。';
  }
  function acceptStatus(result) {
    const expiry = typeof result?.expiresAt === 'number' ? result.expiresAt : Date.parse(result?.expiresAt);
    if (!result?.canReset || !result.challengeId || !Number.isFinite(expiry) || expiry <= now()) return false;
    clearInputs(); state.challengeId = result.challengeId; state.maskedEmail = result.maskedEmail || '';
    state.email = ''; state.step = 'password'; state.notice = ''; expiresAt = expiry; tick(); return true;
  }
  async function restore() {
    if (disposed || state.busy) return;
    const request = start(); state.restoring = true;
    try {
      const result = await api('/api/auth/reset/status', {cache: 'no-store', signal: request.signal});
      if (current(request)) acceptStatus(result);
    } catch (error) {
      failed(error, request, 'restore');
      if (current(request)) state.restoreFailed = true;
    } finally { finish(request); }
  }
  async function sendCode() {
    tick();
    if (disposed || state.busy || state.resendSeconds || state.blockedSeconds || state.step !== 'verify') return;
    const email = state.email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) { state.error = '请输入有效的注册邮箱。'; return; }
    const request = start();
    dropChallenge(); state.notice = ''; state.email = email;
    try {
      const result = await api('/api/auth/forgot', {method: 'POST', body: {email}, signal: request.signal});
      if (!current(request)) return;
      if (!result?.challengeId) throw Error('暂时无法申请验证码，请稍后重试。');
      state.challengeId = result.challengeId;
      expiresAt = now() + duration(result.expiresInSeconds, 600) * 1000;
      resendAt = now() + duration(result.retryAfterSeconds, 60) * 1000;
      state.notice = '请求已受理。如果该邮箱可以找回密码，你会收到验证码，请同时检查垃圾邮件。';
      tick();
    } catch (error) { failed(error, request, 'send'); }
    finally { finish(request); }
  }
  async function verifyCode() {
    tick();
    if (disposed || state.busy || state.blockedSeconds || state.step !== 'verify') return;
    if (!state.challengeId) { state.error = '请先获取验证码。'; return; }
    if (!/^\d{6}$/.test(state.code)) { state.error = '请输入完整的 6 位数字验证码。'; return; }
    const request = start(), challengeId = state.challengeId;
    let verified = false;
    const code = state.code;
    try {
      await api('/api/auth/reset/verify', {method: 'POST', body: {challengeId, code}, signal: request.signal});
      if (!current(request)) return;
      verified = true;
      state.code = '';
      const status = await api('/api/auth/reset/status', {cache: 'no-store', signal: request.signal});
      if (!current(request)) return;
      if (status.challengeId !== challengeId || !acceptStatus(status)) {
        dropChallenge(); state.notice = ''; state.error = '验证状态已失效，请重新获取验证码。';
      }
    } catch (error) {
      failed(error, request, 'verify');
      if (current(request) && verified) state.restoreFailed = true;
    }
    finally { finish(request); }
  }
  async function complete() {
    tick();
    if (disposed || state.busy || state.blockedSeconds || state.step !== 'password') return;
    if (state.password.length < 8 || state.password.length > 24) { state.error = '新密码需为 8 至 24 位。'; return; }
    if (state.password !== state.confirmPassword) { state.error = '两次输入的密码不一致。'; return; }
    const request = start();
    try {
      await api('/api/auth/reset/complete', {method: 'POST', body: {
        challengeId: state.challengeId, password: state.password, confirmPassword: state.confirmPassword,
      }, signal: request.signal});
      if (!current(request)) return;
      reset(); onComplete();
    } catch (error) {
      failed(error, request, 'complete');
      if (current(request)) clearInputs();
    } finally { finish(request); }
  }
  function dispose() {
    disposed = true; reset();
    if (timer !== undefined) clearIntervalFn(timer);
    timer = undefined;
  }
  timer = setIntervalFn(tick, 1000);
  return {restore, sendCode, verifyCode, complete, editEmail, changeEmail, inputCode, reset, dispose, tick};
}
