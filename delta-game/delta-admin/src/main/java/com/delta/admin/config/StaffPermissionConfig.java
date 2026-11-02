package com.delta.admin.config;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class StaffPermissionConfig implements WebMvcConfigurer {
    private final StaffPermissions permissions;
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
            String type;try{type=SecurityUtils.getUserType();}catch(Exception e){return true;}
            if(!Set.of("ADMIN","CS").contains(type))return true;
            String path=req.getServletPath();if(path.isBlank())path=req.getRequestURI();
            if(path.startsWith("/api/"))path=path.substring(4);
            boolean read="GET".equals(req.getMethod());String permission=null;
            if(path.matches("/(admin|cs)/order/[^/]+/refund"))permission="order.refund";
            else if(path.startsWith("/admin/order")||path.startsWith("/cs/order"))permission=read?"order.read":"order.manage";
            else if(path.startsWith("/admin/player")||path.startsWith("/cs/player"))permission="player.manage";
            else if(path.startsWith("/admin/user")||path.startsWith("/cs/user"))permission=read?"user.read":"user.manage";
            else if(path.startsWith("/admin/withdraw")||path.startsWith("/cs/withdraw"))permission="withdraw.review";
            else if(path.startsWith("/admin/statistics"))permission="finance.read";
            else if(path.startsWith("/cs/complaint")||path.startsWith("/cs/relay")||path.startsWith("/cs/replace"))permission="order.manage";
            else if(path.startsWith("/common/chat")||path.startsWith("/cs/chat")||path.startsWith("/admin/chat")||path.startsWith("/system/quick-reply"))permission="chat.manage";
            else if(path.startsWith("/system/")&&!Set.of("/system/config/site","/system/banner/active","/system/notice/active","/system/maintenance/status").contains(path)&&!path.startsWith("/system/content/key/")&&!path.startsWith("/system/quick-reply"))permission="system.manage";
            if(permission!=null)permissions.require(permission);return true;
        }
    });}
}
