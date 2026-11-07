package com.cryptobench.benchmark;

import com.cryptobench.benchmark.mail.MailCipher;
import com.cryptobench.benchmark.mail.MailRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.assertj.core.api.Assertions.*;

/** Real filters, transactions and database; mail stays encrypted in this isolated H2 queue. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reset-code-api-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "benchmark.mail.mode=outbox", "benchmark.mail.outbox=target/test-mail/reset-code-never-dispatched.jsonl",
        "benchmark.mail.worker-enabled=false", "benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetCodeApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MailRepository messages;
    @Autowired MailCipher cipher;
    private static final AtomicInteger IPS=new AtomicInteger();
    private static final String OLD_PASSWORD="old-password8";

    @ParameterizedTest
    @ValueSource(strings={"qq.com","163.com","gmail.com","outlook.com","research.example.org"})
    void acceptsDifferentMailboxProvidersAndCompletesBoundOneTimeReset(String domain)throws Exception{
        Account account=account(domain);Client secondSession=new Client();login(secondSession,account.email(),OLD_PASSWORD,200);
        Client recovery=new Client();Challenge challenge=request(recovery,account.email());
        MvcResult verified=recovery.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code()));
        assertStatus(verified,200);String cookieHeader=verified.getResponse().getHeaders("Set-Cookie").stream().filter(v->v.startsWith("cb_reset_grant=")).findFirst().orElseThrow();
        assertThat(cookieHeader).contains("HttpOnly","SameSite=Strict","Path=/api/auth/reset").doesNotContain("Secure");
        Cookie grant=recovery.cookies.get("cb_reset_grant");assertThat(grant).isNotNull();assertThat(grant.getValue()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(json(verified).toString()).doesNotContain(grant.getValue(),challenge.code(),account.email());
        Client refreshed=recovery.copy();JsonNode status=json(refreshed.get("/api/auth/reset/status"));
        assertThat(status.path("canReset").asBoolean()).isTrue();assertThat(status.path("challengeId").asText()).isEqualTo(challenge.id());
        assertThat(status.path("maskedEmail").asText()).contains("@"+domain).isNotEqualTo(account.email());assertThat(status.hasNonNull("expiresAt")).isTrue();
        assertStatus(new Client().post("/api/auth/reset/complete",completeBody(challenge.id(),"new-password8")),400);
        assertStatus(refreshed.post("/api/auth/reset/complete",completeBody(challenge.id(),"new-password8")),200);
        assertThat(json(refreshed.get("/api/auth/reset/status")).path("canReset").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_sessions WHERE user_id=?",Integer.class,account.id())).isZero();
        assertThat(json(account.loggedIn().get("/api/auth/me")).path("user").isNull()).isTrue();
        assertThat(json(secondSession.get("/api/auth/me")).path("user").isNull()).isTrue();
        assertStatus(recovery.post("/api/auth/reset/complete",completeBody(challenge.id(),"replay-password8")),400);
        assertStatus(new Client().post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code())),400);
        login(new Client(),account.email(),OLD_PASSWORD,401);login(new Client(),account.email(),"new-password8",200);
    }

    @Test void requiresMatchingPasswordsAndEightToTwentyFourCharactersWithoutConsumingGrant()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge challenge=request(client,account.email());verify(client,challenge);
        for(String password:List.of("x".repeat(7),"x".repeat(25)))assertStatus(client.post("/api/auth/reset/complete",completeBody(challenge.id(),password)),400);
        assertStatus(client.post("/api/auth/reset/complete",Map.of("challengeId",challenge.id(),"password","password8","confirmPassword","different8")),400);
        assertThat(json(client.get("/api/auth/reset/status")).path("canReset").asBoolean()).isTrue();
        assertStatus(client.post("/api/auth/reset/complete",completeBody(challenge.id(),"x".repeat(8))),200);
        ageSendWindow(account.email());Challenge next=request(client,account.email());verify(client,next);
        assertStatus(client.post("/api/auth/reset/complete",completeBody(next.id(),"y".repeat(24))),200);
        login(new Client(),account.email(),"y".repeat(24),200);
    }

    @Test void normalizesMailboxCaseAndRejectsMalformedAddressesBeforeCreatingChallenges()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge challenge=request(client,"  "+account.email().toUpperCase(Locale.ROOT)+"  ");
        assertThat(jdbc.queryForObject("SELECT user_id FROM benchmark_password_reset_challenges WHERE id=?",String.class,challenge.id())).isEqualTo(account.id());
        assertStatus(new Client().post("/api/auth/forgot",Map.of("email",account.email())),429);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_challenges",Integer.class);
        for(String email:List.of("","plain-text","name@example.org\r\nBcc: other@example.org","Person <name@example.org>"))assertStatus(new Client().post("/api/auth/forgot",Map.of("email",email)),400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_challenges",Integer.class)).isEqualTo(before);
    }

    @Test void resettingDisabledAccountDoesNotRestoreItsLoginPermission()throws Exception{
        Account account=account("example.com");jdbc.update("UPDATE benchmark_users SET account_status='disabled' WHERE id=?",account.id());
        Client client=new Client();Challenge challenge=request(client,account.email());verify(client,challenge);
        assertStatus(client.post("/api/auth/reset/complete",completeBody(challenge.id(),"new-password8")),200);
        assertThat(jdbc.queryForObject("SELECT account_status FROM benchmark_users WHERE id=?",String.class,account.id())).isEqualTo("disabled");
        login(new Client(),account.email(),"new-password8",403);
    }

    @Test void usesUniformResponsesForKnownUnknownAndDisabledAccountsWithoutLeakingSecrets()throws Exception{
        Account active=account("qq.com"),disabled=account("163.com");jdbc.update("UPDATE benchmark_users SET account_status='disabled' WHERE id=?",disabled.id());
        List<JsonNode> responses=new ArrayList<>();
        for(String email:List.of(active.email(),UUID.randomUUID()+"@outlook.com",disabled.email())){
            MvcResult response=new Client().post("/api/auth/forgot",Map.of("email",email));assertStatus(response,200);JsonNode body=json(response);responses.add(body);
            assertThat(body.path("ok").asBoolean()).isTrue();assertThat(body.path("expiresInSeconds").asInt()).isEqualTo(600);assertThat(body.path("retryAfterSeconds").asInt()).isEqualTo(60);
            assertThat(body.path("challengeId").asText()).hasSizeGreaterThanOrEqualTo(32);
            assertThat(body.toString()).doesNotContain(email,"code_digest","token","resetUrl");
        }
        Set<String> expectedFields=fields(responses.getFirst());for(JsonNode body:responses)assertThat(fields(body)).isEqualTo(expectedFields);
        assertThat(jdbc.queryForObject("SELECT account_status FROM benchmark_users WHERE id=?",String.class,disabled.id())).isEqualTo("disabled");
        Challenge visible=challengeFromResponse(responses.getFirst());String rawGrant;
        Client verified=new Client();verify(verified,visible);rawGrant=verified.cookies.get("cb_reset_grant").getValue();
        String storedGrant=jdbc.queryForObject("SELECT token_hash FROM benchmark_password_reset_grants WHERE challenge_id=?",String.class,visible.id());
        assertThat(storedGrant).hasSize(64).isNotEqualTo(rawGrant).isEqualTo(hash(rawGrant));
        String password=jdbc.queryForObject("SELECT password_hash FROM benchmark_users WHERE id=?",String.class,active.id());assertThat(password).doesNotContain(OLD_PASSWORD);
    }

    @Test void rejectsExpiredCodesAndExpiredOrWrongChallengeGrants()throws Exception{
        Account first=account("example.com"),second=account("example.com");Client client=new Client();Challenge expired=request(client,first.email());
        jdbc.update("UPDATE benchmark_password_reset_challenges SET expires_at=0 WHERE id=?",expired.id());
        assertStatus(client.post("/api/auth/reset/verify",Map.of("challengeId",expired.id(),"code",expired.code())),400);
        ageSendWindow(first.email());Challenge current=request(client,first.email());verify(client,current);
        Challenge foreign=request(new Client(),second.email());
        assertStatus(client.post("/api/auth/reset/complete",completeBody(foreign.id(),"new-password8")),400);
        assertThat(json(client.get("/api/auth/reset/status")).path("canReset").asBoolean()).isTrue();
        jdbc.update("UPDATE benchmark_password_reset_grants SET expires_at=0 WHERE challenge_id=?",current.id());
        assertThat(json(client.get("/api/auth/reset/status")).path("canReset").asBoolean()).isFalse();
        assertStatus(client.post("/api/auth/reset/complete",completeBody(current.id(),"new-password8")),400);
    }

    @Test void commitsFailedAttemptsAndLocksTheFifthFailureInsteadOfRollingBack()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge challenge=request(client,account.email());
        for(int attempt=1;attempt<=5;attempt++){
            assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",wrong(challenge.code()))));
            assertThat(jdbc.queryForObject("SELECT failed_attempts FROM benchmark_password_reset_challenges WHERE id=?",Integer.class,challenge.id())).isEqualTo(attempt);
            assertThat(events("failed_email",account.email())).isEqualTo(attempt);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM benchmark_password_reset_challenges WHERE id=?",String.class,challenge.id())).isEqualTo("locked");
        assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code())));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_grants WHERE challenge_id=?",Integer.class,challenge.id())).isZero();
    }

    @Test void concurrentWrongCodesBothCommitTheirFailedAttempt()throws Exception{
        Account account=account("example.com");Client first=new Client(),second=new Client();Challenge challenge=request(first,account.email());first.csrf();second.csrf();
        List<MvcResult> responses=concurrently(()->first.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",wrong(challenge.code()))),()->second.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",wrong(challenge.code()))));
        responses.forEach(this::assertClientError);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM benchmark_password_reset_challenges WHERE id=?",Integer.class,challenge.id())).isEqualTo(2);
        assertThat(events("failed_email",account.email())).isEqualTo(2);
        verify(first,challenge);
    }

    @Test void resendInvalidatesPriorCodeAndGrantButPreservesMailboxFailureWindow()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge initial=request(client,account.email());
        for(int i=0;i<4;i++)assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",initial.id(),"code",wrong(initial.code()))));
        ageSendWindow(account.email());Challenge replacement=request(client,account.email());
        assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",initial.id(),"code",initial.code())));
        assertThat(events("failed_email",account.email())).isGreaterThanOrEqualTo(4);
        for(int i=0;i<5;i++)assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",replacement.id(),"code",wrong(replacement.code()))));
        ageSendWindow(account.email());Challenge third=request(client,account.email());
        if(events("failed_email",account.email())<10)assertClientError(client.post("/api/auth/reset/verify",Map.of("challengeId",third.id(),"code",wrong(third.code()))));
        assertStatus(client.post("/api/auth/reset/verify",Map.of("challengeId",third.id(),"code",third.code())),429);
        assertThat(events("failed_email",account.email())).isGreaterThanOrEqualTo(10);
        jdbc.update("UPDATE benchmark_password_reset_rate_events SET created_at=? WHERE event_scope='failed_email' AND subject_hash=?",System.currentTimeMillis()-901_000,hash(account.email()));
        ageSendWindow(account.email());Challenge afterWindow=request(client,account.email());verify(client,afterWindow);Client oldGrant=client.copy();ageSendWindow(account.email());request(client,account.email());
        assertThat(json(oldGrant.get("/api/auth/reset/status")).path("canReset").asBoolean()).isFalse();
        assertStatus(oldGrant.post("/api/auth/reset/complete",completeBody(afterWindow.id(),"new-password8")),400);
    }

    @Test void enforcesPersistentCooldownAndFiveSendsPerEmailPerHour()throws Exception{
        Account account=account("example.com");Client client=new Client();request(client,account.email());
        MvcResult cooldown=client.post("/api/auth/forgot",Map.of("email",account.email()));assertStatus(cooldown,429);assertThat(cooldown.getResponse().getHeader("Retry-After")).isNotBlank();
        for(int i=1;i<5;i++){ageSendWindow(account.email());request(client,account.email());}
        ageSendWindow(account.email());assertStatus(new Client().post("/api/auth/forgot",Map.of("email",account.email())),429);
        assertThat(events("send_email",account.email())).isEqualTo(5);
        jdbc.update("UPDATE benchmark_password_reset_rate_events SET created_at=? WHERE event_scope='send_email' AND subject_hash=?",System.currentTimeMillis()-3_601_000,hash(account.email()));
        request(new Client(),account.email());
    }

    @Test void enforcesTwentySendsAndFiftyVerificationsPerIpWithoutTrustingForwardedHeader()throws Exception{
        Client client=new Client();
        for(int i=0;i<20;i++)assertStatus(client.post("/api/auth/forgot",Map.of("email",UUID.randomUUID()+"@unknown.example.org")),200);
        assertThat(events("send_ip",client.ip)).isEqualTo(20);
        assertStatus(client.postWithForwarded("/api/auth/forgot",Map.of("email",UUID.randomUUID()+"@unknown.example.org"),"203.0.113.42"),429);
        Client verifier=new Client();Account account=account("example.com");Challenge challenge=request(new Client(),account.email());
        for(int i=0;i<50;i++)assertClientError(verifier.post("/api/auth/reset/verify",Map.of("challengeId","reset_"+UUID.randomUUID(),"code","000000")));
        assertThat(events("verify_ip",verifier.ip)).isEqualTo(50);
        assertStatus(verifier.postWithForwarded("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code()),"203.0.113.43"),429);
        assertThat(jdbc.queryForObject("SELECT status FROM benchmark_password_reset_challenges WHERE id=?",String.class,challenge.id())).isEqualTo("active");
        jdbc.update("UPDATE benchmark_password_reset_rate_events SET created_at=? WHERE event_scope='verify_ip' AND subject_hash=?",System.currentTimeMillis()-301_000,hash(verifier.ip));
        verify(verifier,challenge);
    }

    @Test void csrfIsRequiredForForgotVerifyAndComplete()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge challenge=request(client,account.email());verify(client,challenge);
        for(String path:List.of("/api/auth/forgot","/api/auth/reset/verify","/api/auth/reset/complete")){
            var request=MockMvcRequestBuilders.post(path).contentType("application/json").content(mapper.writeValueAsBytes(completeBody(challenge.id(),"new-password8")));
            request.cookie(client.cookies.get("cb_reset_grant"));assertStatus(mvc.perform(request).andReturn(),403);
        }
        assertThat(json(client.get("/api/auth/reset/status")).path("canReset").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_grants WHERE challenge_id=? AND used_at IS NULL",Integer.class,challenge.id())).isEqualTo(1);
    }

    @Test void missingCodeKeyDoesNotSpendTheUsersVerificationAttempts()throws Exception{
        Account account=account("example.com");Client client=new Client();Challenge challenge=request(client,account.email());
        String key=jdbc.queryForObject("SELECT code_key_id FROM benchmark_password_reset_challenges WHERE id=?",String.class,challenge.id());
        jdbc.update("UPDATE benchmark_password_reset_challenges SET code_key_id=? WHERE id=?","f".repeat(32),challenge.id());
        MvcResult unavailable=client.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code()));
        assertStatus(unavailable,503);assertThat(json(unavailable).path("code").asText()).isEqualTo("reset_delivery_unavailable");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM benchmark_password_reset_challenges WHERE id=?",Integer.class,challenge.id())).isZero();
        assertThat(events("failed_email",account.email())).isZero();
        jdbc.update("UPDATE benchmark_password_reset_challenges SET code_key_id=? WHERE id=?",key,challenge.id());
        verify(client,challenge);
    }

    @Test void concurrentRequestsPermitOnlyOneSendOneVerificationAndOneCompletion()throws Exception{
        Account account=account("example.com");Client first=new Client(),second=new Client();first.csrf();second.csrf();
        List<MvcResult> sends=concurrently(()->first.post("/api/auth/forgot",Map.of("email",account.email())),()->second.post("/api/auth/forgot",Map.of("email",account.email())));
        assertThat(sends.stream().map(r->r.getResponse().getStatus())).containsExactlyInAnyOrder(200,429);
        Challenge challenge=challengeFromResponse(json(sends.stream().filter(r->r.getResponse().getStatus()==200).findFirst().orElseThrow()));
        List<MvcResult> verified=concurrently(()->first.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code())),()->second.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code())));
        assertThat(verified.stream().filter(r->r.getResponse().getStatus()==200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_grants WHERE challenge_id=?",Integer.class,challenge.id())).isEqualTo(1);
        Client granted=first.cookies.containsKey("cb_reset_grant")?first:second;Client duplicate=granted.copy();
        List<MvcResult> completed=concurrently(()->granted.post("/api/auth/reset/complete",completeBody(challenge.id(),"new-password8")),()->duplicate.post("/api/auth/reset/complete",completeBody(challenge.id(),"other-password8")));
        assertThat(completed.stream().filter(r->r.getResponse().getStatus()==200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_grants WHERE challenge_id=? AND used_at IS NOT NULL",Integer.class,challenge.id())).isEqualTo(1);
    }

    private record Account(String id,String email,Client loggedIn){}
    private record Challenge(String id,String code){}
    private Account account(String domain)throws Exception{
        Client client=new Client();String email="reset-"+UUID.randomUUID()+"@"+domain;
        MvcResult response=client.post("/api/auth/register",Map.of("email",email,"name","Reset test","password",OLD_PASSWORD));assertStatus(response,201);
        return new Account(json(response).path("user").path("id").asText(),email,client);
    }
    private Challenge request(Client client,String email)throws Exception{
        MvcResult response=client.post("/api/auth/forgot",Map.of("email",email));assertStatus(response,200);return challengeFromResponse(json(response));
    }
    private Challenge challengeFromResponse(JsonNode response)throws Exception{
        String id=response.path("challengeId").asText();String messageId=jdbc.queryForObject("SELECT id FROM benchmark_mail_messages WHERE purpose='password_reset_code' AND reference_id=?",String.class,id);
        var message=messages.find(messageId).orElseThrow();JsonNode payload=mapper.readTree(cipher.decrypt(message.id(),message.purpose(),message.reference(),message.encrypted()));
        String code=payload.path("code").asText();assertThat(code).matches("[0-9]{6}");
        String digest=jdbc.queryForObject("SELECT code_digest FROM benchmark_password_reset_challenges WHERE id=?",String.class,id);
        assertThat(digest).hasSize(64).isNotEqualTo(code).isNotEqualTo(hash(code));
        assertThat(jdbc.queryForObject("SELECT email_key FROM benchmark_password_reset_challenges WHERE id=?",String.class,id)).hasSize(64).doesNotContain("@");
        assertThat(message.encrypted().ciphertext()).doesNotContain(payload.path("email").asText(),"\"code\"",code);
        assertThat(response.toString()).doesNotContain(code,"resetUrl");return new Challenge(id,code);
    }
    private void verify(Client client,Challenge challenge)throws Exception{assertStatus(client.post("/api/auth/reset/verify",Map.of("challengeId",challenge.id(),"code",challenge.code())),200);}
    private Map<String,String> completeBody(String id,String password){return Map.of("challengeId",id,"password",password,"confirmPassword",password);}
    private void login(Client client,String email,String password,int expected)throws Exception{assertStatus(client.post("/api/auth/login",Map.of("email",email,"password",password)),expected);}
    private void ageSendWindow(String email){jdbc.update("UPDATE benchmark_password_reset_rate_events SET created_at=created_at-61000 WHERE event_scope='send_email' AND subject_hash=?",hash(email));}
    private int events(String scope,String subject){return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_rate_events WHERE event_scope=? AND subject_hash=?",Integer.class,scope,hash(subject));}
    private String wrong(String code){return "000000".equals(code)?"000001":"000000";}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
    private Set<String> fields(JsonNode node){Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);return fields;}
    private JsonNode json(MvcResult response)throws Exception{return mapper.readTree(response.getResponse().getContentAsByteArray());}
    private void assertStatus(MvcResult response,int expected)throws Exception{assertThat(response.getResponse().getStatus()).withFailMessage("HTTP %s: %s",response.getResponse().getStatus(),response.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expected);}
    private void assertClientError(MvcResult response){assertThat(response.getResponse().getStatus()).isIn(400,429);}
    private List<MvcResult> concurrently(Callable<MvcResult> one,Callable<MvcResult> two)throws Exception{
        try(var executor=Executors.newFixedThreadPool(2)){
            CountDownLatch start=new CountDownLatch(1);Future<MvcResult> first=executor.submit(()->{start.await();return one.call();}),second=executor.submit(()->{start.await();return two.call();});start.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
    }
    private class Client {
        final Map<String,Cookie> cookies=new HashMap<>();final String ip;String csrfHeader,csrfToken;
        Client(){int value=IPS.incrementAndGet();ip="198.18."+(value/250)+"."+(value%250+1);}
        Client(String ip){this.ip=ip;}
        Client copy(){Client value=new Client(ip);value.cookies.putAll(cookies);value.csrfHeader=csrfHeader;value.csrfToken=csrfToken;return value;}
        void csrf()throws Exception{JsonNode value=json(get("/api/auth/csrf"));csrfHeader=value.path("headerName").asText();csrfToken=value.path("token").asText();}
        MvcResult get(String path)throws Exception{return send("GET",path,null,null);}
        MvcResult post(String path,Object body)throws Exception{return send("POST",path,body,null);}
        MvcResult postWithForwarded(String path,Object body,String forwarded)throws Exception{return send("POST",path,body,forwarded);}
        private MvcResult send(String method,String path,Object body,String forwarded)throws Exception{
            var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).with(req->{req.setRemoteAddr(ip);return req;});
            if(!method.equals("GET")){if(csrfToken==null)csrf();request.header(csrfHeader,csrfToken);}
            if(forwarded!=null)request.header("X-Forwarded-For",forwarded);
            List<Cookie> applicable=cookies.values().stream().filter(cookie->cookie.getPath()==null||path.startsWith(cookie.getPath())).toList();
            if(!applicable.isEmpty())request.cookie(applicable.toArray(Cookie[]::new));
            if(body!=null)request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            MvcResult response=mvc.perform(request).andReturn();for(Cookie cookie:response.getResponse().getCookies()){if(cookie.getMaxAge()==0)cookies.remove(cookie.getName());else cookies.put(cookie.getName(),cookie);}
            return response;
        }
    }
}
