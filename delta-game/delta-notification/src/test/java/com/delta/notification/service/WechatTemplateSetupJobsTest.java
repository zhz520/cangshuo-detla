package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.security.SecretCipher;
import com.delta.platform.PlatformService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatTemplateSetupJobsTest {
    final String state="z".repeat(43),domain="test.invalid", id=UUID.randomUUID().toString();
    final WechatOpenPlatformClient client=mock(WechatOpenPlatformClient.class);
    final WechatAccountService accounts=mock(WechatAccountService.class);
    final WechatTemplateProvisioner provisioner=mock(WechatTemplateProvisioner.class);
    final PlatformService platform=mock(PlatformService.class);
    JdbcTemplate jdbc; WechatTemplateSetupJobs jobs;
    @BeforeEach void prepare() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);jdbc.execute(Files.readString(Path.of("../../deploy/baota/migrations/20261123-wechat-template-setup.sql")));
        jobs=new WechatTemplateSetupJobs(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),new SecretCipher("test-only"),client,accounts,provisioner,platform,null);
        when(client.start()).thenReturn(new WechatOpenPlatformClient.Authorization(state,"https://example.invalid/scan",Instant.now().plusSeconds(300).toString()));
    }
    @AfterEach void close(){jobs.stop();}
    void due(){jdbc.update("UPDATE wechat_template_setup_job SET next_run=DATEADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE status IN ('PENDING','PROCESSING')");}
    @Test void scanCompletesAndProvisionsWithoutBrowserAndPollingDoesNotRepeatWork() throws Exception {
        var account=Map.<String,Object>of("id",id,"mode","OPEN_PLATFORM","enabled",true);
        when(accounts.accounts()).thenReturn(List.of(account));
        when(client.reconcileAttempt(state,accounts)).thenReturn(new WechatOpenPlatformClient.Progress("AUTHORIZED",account,true));
        when(provisioner.provision(id)).thenReturn(Map.of("configured",2,"preserved",0,"issues",List.of()));
        jobs.start(domain);
        assertThat(jdbc.queryForObject("SELECT state_cipher FROM wechat_template_setup_job",String.class)).doesNotContain(state);
        due();jobs.drain("SCAN");due();jobs.drain("SETUP");
        assertThat(jobs.report(id)).containsEntry("status","DONE").containsEntry("configured",2);
        assertThat(jobs.progress(state).account()).isEqualTo(account); jobs.progress(state);jobs.drain("SETUP");
        verify(provisioner).provision(id);verify(client,times(2)).verifyAttemptOwner(state);
        assertThat(jdbc.queryForObject("SELECT state_cipher FROM wechat_template_setup_job WHERE kind='SCAN'",String.class)).isNull();
    }
    @Test void overlappingRetriesAreCoalescedAndFeatureOrModeFailuresNeverProvision() throws Exception {
        when(accounts.accounts()).thenReturn(List.of(Map.of("id",id,"mode","DIRECT","enabled",true)));
        assertThatThrownBy(()->jobs.retry(id,domain)).isInstanceOf(BusinessException.class);
        when(accounts.accounts()).thenReturn(List.of(Map.of("id",id,"mode","OPEN_PLATFORM","enabled",true)));
        jobs.retry(id,domain);jobs.retry(id,domain);
        doThrow(new BusinessException(403,"test")).when(platform).requireFeature(OfficialWechatService.FEATURE_ID,domain);
        due();jobs.drain("SETUP");assertThat(jobs.report(id)).containsEntry("status","ATTENTION");verifyNoInteractions(provisioner);
        doNothing().when(platform).requireFeature(OfficialWechatService.FEATURE_ID,domain);
        when(provisioner.provision(id)).thenReturn(Map.of("configured",0,"preserved",1,"issues",List.of(Map.of("reason","缺模板"))));
        jobs.retry(id,domain);due();jobs.drain("SETUP");verify(provisioner).provision(id);
        assertThat(jobs.report(id)).containsEntry("status","ATTENTION");
    }
    @Test void liveLeaseSurvivesRetryAndExpiredLeaseIsReclaimed() throws Exception {
        when(accounts.accounts()).thenReturn(List.of(Map.of("id",id,"mode","OPEN_PLATFORM","enabled",true)));
        when(provisioner.provision(id)).thenReturn(Map.of("issues",List.of(),"configured",1,"preserved",0));
        jobs.retry(id,domain);
        jdbc.update("UPDATE wechat_template_setup_job SET status='PROCESSING',lease_id='old',next_run=DATEADD(MINUTE,1,CURRENT_TIMESTAMP)");
        jobs.retry(id,domain);jobs.drain("SETUP");
        verifyNoInteractions(provisioner);
        due();jobs.drain("SETUP");jobs.drain("SETUP");
        verify(provisioner).provision(id); assertThat(jobs.report(id)).containsEntry("status","DONE");
    }
    @Test void expiredScanCannotImportOrQueue() {
        jobs.start(domain);jdbc.update("UPDATE wechat_template_setup_job SET expires_at=DATEADD(SECOND,-1,CURRENT_TIMESTAMP)");due();jobs.drain("SCAN");
        assertThat(jobs.progress(state).status()).isEqualTo("EXPIRED");verifyNoInteractions(provisioner,accounts);
        doThrow(new BusinessException(404,"授权会话不存在")).when(client).verifyAttemptOwner(state);
        assertThatThrownBy(()->jobs.progress(state)).isInstanceOf(BusinessException.class);
    }
}
