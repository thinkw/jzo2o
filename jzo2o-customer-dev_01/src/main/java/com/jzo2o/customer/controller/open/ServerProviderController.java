package com.jzo2o.customer.controller.open;

import com.jzo2o.common.constants.UserType;
import com.jzo2o.customer.model.dto.request.InstitutionRegisterReqDTO;
import com.jzo2o.customer.model.dto.request.LoginForCustomerReqDTO;
import com.jzo2o.customer.model.dto.request.LoginForWorkReqDTO;
import com.jzo2o.customer.model.dto.response.LoginResDTO;
import com.jzo2o.customer.service.IServeProviderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @author itcast
 */
@RestController("openServerProviderController")
@RequestMapping("/open/serve-provider")
@Api(tags = "白名单接口 - 客户注册相关接口")
public class ServerProviderController {

    @Resource
    private IServeProviderService serveProviderService;

    @PostMapping("/institution/register")
    @ApiOperation("机构注册接口")
    public void institutionRegister(@RequestBody InstitutionRegisterReqDTO institutionRegisterReqDTO){
        serveProviderService.registerInstitution(institutionRegisterReqDTO);
    }
}
