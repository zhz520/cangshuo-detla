package com.delta.player.service;

import com.alibaba.fastjson2.JSON;
import com.delta.player.dto.PlayerHomepageRequest;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** The media gateway must OCR images and transcribe audio before returning SAFE or BLOCK. */
@Service
@RequiredArgsConstructor
public class MediaRiskService {
    private final SysConfigService configs;
    private final JdbcTemplate jdbc;
    public record Result(String status,String reason) {}
    public Result check(PlayerHomepageRequest body) {
        String text=Objects.toString(body.getNickname(),"")+" "+Objects.toString(body.getIntroduction(),"")+" "+Objects.toString(body.getStory(),"");
        if(Pattern.compile("外挂|代练|私下交易|加[微vVＶ]|微信[号:]|QQ[群号:]|https?://|[0-9]{8,}",Pattern.CASE_INSENSITIVE).matcher(text).find())return new Result("REJECTED","命中导流或违规服务规则，请修改内容");
        for(var row:jdbc.queryForList("SELECT word FROM sensitive_word WHERE status=1"))if(text.toLowerCase(Locale.ROOT).contains(String.valueOf(row.get("word")).toLowerCase(Locale.ROOT)))return new Result("REJECTED","命中平台敏感词规则，请修改内容");
        boolean media=nonempty(body.getAvatar())||nonempty(body.getCoverImage())||nonempty(body.getVoiceUrl())||body.getAlbumImages()!=null&&!body.getAlbumImages().isEmpty();
        if(!media)return new Result("PENDING_REVIEW","文本系统检测通过，等待人工审核");
        String endpoint=configs.getConfigValue("risk.media.endpoint",""),token=configs.getConfigValue("risk.media.token","");
        if(endpoint.isBlank())return new Result("PENDING_SYSTEM","图片/语音检测服务未配置，资料保留待检测，当前公开资料不变");
        try {
            URI uri=URI.create(endpoint);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)throw new IllegalArgumentException();
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).header("Content-Type","application/json").header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("text",text,"profile",body)))).build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>10000)throw new IllegalStateException();
            String result=JSON.parseObject(response.body()).getString("verdict");
            if("SAFE".equals(result))return new Result("PENDING_REVIEW","图片/语音及文本系统检测通过，等待人工审核");
            if("BLOCK".equals(result))return new Result("REJECTED","图片或语音检测未通过，请修改内容");
        }catch(Exception ignored){}
        return new Result("PENDING_SYSTEM","图片/语音检测暂不可用，请稍后重试");
    }
    private boolean nonempty(String value){return value!=null&&!value.isBlank();}
}
