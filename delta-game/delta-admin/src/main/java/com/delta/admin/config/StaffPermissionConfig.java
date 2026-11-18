package com.delta.admin.config;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.*;
import org.springframework.web.method.HandlerMethod;
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
            // Dedicated endpoints enforce their own permission before entering the service layer.
            // Do not add a broader route permission on top of a user.manage/finance.read grant.
            if(handler instanceof HandlerMethod method){
                PreAuthorize annotation=AnnotationUtils.findAnnotation(method.getMethod(),PreAuthorize.class);
                if(annotation!=null&&annotation.value().contains("@staffPermissions.allowed"))return true;
            }
            String path=req.getServletPath();if(path.isBlank())path=req.getRequestURI();
            if(path.startsWith("/api/"))path=path.substring(4);
            boolean read="GET".equals(req.getMethod());String permission=null;
            if(path.matches("/(admin|cs)/order/[^/]+/refund"))permission="order.refund";
            else if(path.matches("/admin/user/[^/]+/balance"))permission="finance.manage";
            else if(path.startsWith("/admin/order")||path.startsWith("/cs/order"))permission=read?"order.read":"order.manage";
            else if(path.startsWith("/admin/player")||path.startsWith("/cs/player"))permission="player.manage";
            else if(path.startsWith("/admin/user")||path.startsWith("/cs/user"))permission=read?"user.read":"user.manage";
            else if(path.startsWith("/admin/withdraw")||path.startsWith("/cs/withdraw"))permission="withdraw.review";
            else if(path.startsWith("/cs/deposit-refunds"))permission="withdraw.review";
            else if(path.startsWith("/admin/statistics")||path.startsWith("/admin/analytics"))permission="finance.read";
            else if(path.startsWith("/cs/analytics"))permission="order.read";
            else if(path.startsWith("/admin/rewards"))permission=read?"finance.read":"finance.manage";
            else if(path.startsWith("/cs/complaint")||path.startsWith("/cs/relay")||path.startsWith("/cs/replace"))permission="order.manage";
            else if(path.startsWith("/common/chat")||path.startsWith("/cs/chat")||path.startsWith("/admin/chat")||path.startsWith("/system/quick-reply"))permission="chat.manage";
            else if(!read&&(path.startsWith("/product/")||path.equals("/product")))permission="product.manage";
            else if((path.startsWith("/admin/recommend-category")||path.startsWith("/admin/category-field")))permission="product.manage";
            else if(!read&&(path.startsWith("/cs/product")||path.startsWith("/cs/category")))permission="product.manage";
            else if(path.equals("/pay/config/upload"))permission="system.manage";
            else if(path.startsWith("/system/")&&!Set.of("/system/config/site","/system/banner/active","/system/notice/active","/system/maintenance/status").contains(path)&&!path.startsWith("/system/content/key/")&&!path.startsWith("/system/quick-reply"))permission="system.manage";
            // Any new privileged route must opt in explicitly. Read-only order/catalog
            // surfaces used by the CS app remain available under their existing grants.
            else if(path.startsWith("/admin/"))permission="system.manage";
            if(permission!=null)permissions.require(permission);return true;
        }
    });}
}
