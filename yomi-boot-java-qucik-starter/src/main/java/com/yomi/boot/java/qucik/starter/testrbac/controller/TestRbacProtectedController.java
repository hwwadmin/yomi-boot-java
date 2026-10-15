package com.yomi.boot.java.qucik.starter.testrbac.controller;

import com.yomi.boot.java.base.model.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受 RBAC 拦截器保护的探测接口（不在 token/rbac 的 /test/ds/** 放行列表内）。
 * <p>
 * 鉴权仅由 {@link com.yomi.boot.java.component.rbac.web.RbacInterceptor} 完成。
 */
@RestController
@RequestMapping("/test/rbac")
public class TestRbacProtectedController {

    @GetMapping("/protected")
    public Response<String> protectedResource() {
        return Response.success("rbac-protected-ok");
    }

}
