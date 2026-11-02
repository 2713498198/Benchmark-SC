import {reactive, watch, onMounted, onUnmounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp} from './shared.js';
import {createPasswordRecovery} from '../password-recovery.js';

export default {
  setup() {
    const app = useApp(), recovery = reactive({});
    const workflow = createPasswordRecovery({state: recovery, api: app.api, onComplete() {
      app.applyBootstrap({});
      app.toast('密码已重置，请使用新密码登录');
      app.navigate('/login');
      void app.hydrate();
    }});
    watch(() => app.state.user?.id, workflow.reset, {flush: 'sync'});
    watch(app.route, workflow.reset, {flush: 'sync'});
    onMounted(async () => { await app.hydrate(); await workflow.restore(); });
    onUnmounted(workflow.dispose);
    return {recovery, ...workflow};
  },
  template: `<section class="password-recovery" aria-labelledby="recovery-title" data-password-recovery>
    <h1 id="recovery-title">{{recovery.step==='password'?'设置新密码':'找回密码'}}</h1>
    <ol class="recovery-steps" aria-label="找回密码进度">
      <li :aria-current="recovery.step==='verify'?'step':null"><span>1</span>验证邮箱</li>
      <li :aria-current="recovery.step==='password'?'step':null"><span>2</span>设置密码</li>
    </ol>
    <p v-if="recovery.restoring" role="status">正在检查验证状态…</p>
    <template v-else>
      <p v-if="recovery.error" class="error-text" role="alert" data-recovery-error>{{recovery.error}}</p>
      <p v-if="recovery.blockedSeconds" class="inline-note" role="status">请在 {{recovery.blockedSeconds}} 秒后重试。</p>
      <button v-if="recovery.restoreFailed" class="btn small" type="button" :disabled="recovery.busy" @click="restore">重新检查验证状态</button>
      <form v-if="recovery.step==='verify'" class="stack" @submit.prevent="recovery.challengeId?verifyCode():sendCode()" data-recovery-verify>
        <p>输入注册时使用的邮箱，获取验证码后继续。</p>
        <div class="field">
          <label for="recovery-email">注册邮箱</label>
          <input id="recovery-email" name="email" type="email" :value="recovery.email" @input="editEmail($event.target.value)" autocomplete="email" maxlength="254" placeholder="yourname@example.com" required>
        </div>
        <button class="btn" type="button" data-recovery-send :disabled="recovery.busy||recovery.resendSeconds>0||recovery.blockedSeconds>0||!recovery.email" @click="sendCode">{{recovery.resendSeconds?recovery.resendSeconds+' 秒后可重新获取':recovery.busy?'正在处理…':recovery.challengeId?'重新获取验证码':'获取验证码'}}</button>
        <p v-if="recovery.notice" class="inline-note" role="status" data-recovery-notice>{{recovery.notice}}</p>
        <template v-if="recovery.challengeId">
          <div class="field">
            <label for="recovery-code">6 位验证码</label>
            <input id="recovery-code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" :value="recovery.code" @input="inputCode($event.target.value)" pattern="[0-9]{6}" maxlength="6" :disabled="recovery.busy" aria-describedby="recovery-code-help" placeholder="请输入 6 位数字" required>
            <small id="recovery-code-help">请在验证码有效期内完成验证；重新获取后请使用最新验证码。</small>
          </div>
          <button class="btn primary wide" type="submit" :disabled="recovery.busy||recovery.blockedSeconds>0||recovery.code.length!==6">{{recovery.busy?'正在验证…':'下一步'}}</button>
        </template>
      </form>
      <form v-else class="stack" @submit.prevent="complete" data-recovery-password>
        <p>已验证邮箱 <strong>{{recovery.maskedEmail||'你的注册邮箱'}}</strong>。请设置 8 至 24 位的新密码。</p>
        <div class="field">
          <label for="recovery-password">新密码</label>
          <div class="recovery-password-input"><input id="recovery-password" name="new-password" :type="recovery.showPassword?'text':'password'" v-model="recovery.password" autocomplete="new-password" minlength="8" maxlength="24" :disabled="recovery.busy" required><button class="btn small" type="button" :aria-pressed="String(recovery.showPassword)" :disabled="recovery.busy" @click="recovery.showPassword=!recovery.showPassword">{{recovery.showPassword?'隐藏新密码':'显示新密码'}}</button></div>
        </div>
        <div class="field">
          <label for="recovery-confirm">确认新密码</label>
          <div class="recovery-password-input"><input id="recovery-confirm" name="confirm-password" :type="recovery.showConfirmation?'text':'password'" v-model="recovery.confirmPassword" autocomplete="new-password" minlength="8" maxlength="24" :disabled="recovery.busy" required><button class="btn small" type="button" :aria-pressed="String(recovery.showConfirmation)" :disabled="recovery.busy" @click="recovery.showConfirmation=!recovery.showConfirmation">{{recovery.showConfirmation?'隐藏确认密码':'显示确认密码'}}</button></div>
        </div>
        <button class="btn primary wide" type="submit" :disabled="recovery.busy||recovery.blockedSeconds>0">{{recovery.busy?'正在重置…':'确认重置密码'}}</button>
        <button class="text-link" type="button" :disabled="recovery.busy" @click="changeEmail">重新验证邮箱</button>
      </form>
    </template>
    <div class="login-links"><a href="#/login" class="text-link">返回登录</a></div>
  </section>`,
};
