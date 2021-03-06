package com.ruoyi.iot.service.impl;

import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.iot.domain.ProductAuthorize;
import com.ruoyi.iot.mapper.ProductAuthorizeMapper;
import com.ruoyi.iot.model.MqttAuthenticationModel;
import com.ruoyi.iot.model.ProductAuthenticateModel;
import com.ruoyi.iot.model.RegisterUserInput;
import com.ruoyi.iot.service.IDeviceService;
import com.ruoyi.iot.service.IToolService;
import com.ruoyi.iot.util.AESUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.iot.mqtt.MqttConfig;

import java.util.List;
import java.util.Random;

/**
 * 
 * @author kerwincui
 * @date 2021-12-16
 */
@Service
public class ToolServiceImpl implements IToolService
{
    private static final Logger log = LoggerFactory.getLogger(ToolServiceImpl.class);

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private ProductAuthorizeMapper productAuthorizeMapper;

    @Autowired
    private MqttConfig mqttConfig;

    @Autowired
    @Lazy
    private IDeviceService deviceService;

    /**
     * ???????????????????????????
     */
    @Override
    public String getStringRandom(int length) {
        String val = "";
        Random random = new Random();
        //??????length??????????????????????????????
        for(int i = 0; i < length; i++) {
            String charOrNum = random.nextInt(2) % 2 == 0 ? "char" : "num";
            //????????????????????????
            if( "char".equalsIgnoreCase(charOrNum) ) {
                //???????????????????????????????????????
                // int temp = random.nextInt(2) % 2 == 0 ? 65 : 97;
                val += (char)(random.nextInt(26) + 65);
            } else if( "num".equalsIgnoreCase(charOrNum) ) {
                val += String.valueOf(random.nextInt(10));
            }
        }
        return val;
    }

    /**
     * ??????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String register(RegisterUserInput registerBody)
    {
        String msg = "";
        String username = registerBody.getUsername();
        String password = registerBody.getPassword();
        String phonenumber=registerBody.getPhonenumber();

        boolean captchaOnOff = configService.selectCaptchaOnOff();
        // ???????????????
        if (captchaOnOff)
        {
            validateCaptcha(username, registerBody.getCode(), registerBody.getUuid());
        }

        if (StringUtils.isEmpty(username))
        {
            msg = "?????????????????????";
        }
        else if (StringUtils.isEmpty(password))
        {
            msg = "????????????????????????";
        }
        else if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            msg = "?????????????????????2???20???????????????";
        }
        else if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            msg = "?????????????????????5???20???????????????";
        }
        else if (UserConstants.NOT_UNIQUE.equals(userService.checkUserNameUnique(username)))
        {
            msg = "????????????'" + username + "'??????????????????????????????";
        }else if (UserConstants.NOT_UNIQUE.equals(checkPhoneUnique(phonenumber)))
        {
            msg = "????????????'" + username + "'????????????????????????????????????";
        }
        else
        {
            SysUser sysUser = new SysUser();
            sysUser.setUserName(username);
            sysUser.setNickName(username);
            sysUser.setPhonenumber(phonenumber);
            sysUser.setPassword(SecurityUtils.encryptPassword(registerBody.getPassword()));
            boolean regFlag = userService.registerUser(sysUser);
            //????????????????????????(1=??????????????????2=???????????????3=???????????????4=??????)
            Long[] roleIds={3L};
            userService.insertUserAuth(sysUser.getUserId(),roleIds);
            if (!regFlag)
            {
                msg = "????????????,???????????????????????????";
            }
            else
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                        MessageUtils.message("user.register.success")));
            }
        }
        return msg;
    }

    /**
     * ????????????????????????????????????
     *
     * @param user ????????????
     * @return ????????????????????????
     */
    @Override
    public List<SysUser> selectUserList(SysUser user)
    {
        return userMapper.selectUserList(user);
    }

    /**
     * ??????????????????????????????
     *
     * @param phonenumber ????????????
     * @return
     */
    public String checkPhoneUnique(String phonenumber)
    {
        SysUser info = userMapper.checkPhoneUnique(phonenumber);
        if (StringUtils.isNotNull(info))
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * ???????????????
     *
     * @param username ?????????
     * @param code ?????????
     * @param uuid ????????????
     * @return ??????
     */
    public void validateCaptcha(String username, String code, String uuid)
    {
        String verifyKey = Constants.CAPTCHA_CODE_KEY + uuid;
        String captcha = redisCache.getCacheObject(verifyKey);
        redisCache.deleteObject(verifyKey);
        if (captcha == null)
        {
            throw new CaptchaExpireException();
        }
        if (!code.equalsIgnoreCase(captcha))
        {
            throw new CaptchaException();
        }
    }

    /**
     * ??????????????????
     */
    @Override
    public ResponseEntity simpleMqttAuthentication(MqttAuthenticationModel mqttModel, ProductAuthenticateModel productModel) {
        // 1=???????????????2=???????????????3=??????+????????????
        if(productModel.getVertificateMethod()!=1 && productModel.getVertificateMethod()!=3){
            return returnUnauthorized(mqttModel, "????????????????????????????????????????????????????????????");
        }
        String[] passwordArray = mqttModel.getPassword().split("&");
        if (productModel.getIsAuthorize() == 1 && passwordArray.length != 2) {
            return returnUnauthorized(mqttModel, "???????????????????????????????????????????????????????????????????????? & ?????????");
        }
        String mqttPassword = passwordArray[0];
        String authCode = passwordArray.length == 2 ? passwordArray[1] : "";
        // ???????????????
        if (!mqttModel.getUserName().equals(productModel.getMqttAccount())) {
            return returnUnauthorized(mqttModel, "???????????????????????????mqtt???????????????");
        }
        // ????????????
        if (!mqttPassword.equals(productModel.getMqttPassword())) {
            return returnUnauthorized(mqttModel, "???????????????????????????mqtt????????????");
        }
        // ???????????????
        if (productModel.getIsAuthorize() == 1) {
            // ????????????????????????
            String resultMessage = authCodeProcess(authCode, mqttModel, productModel);
            if (!resultMessage.equals("")) {
                return returnUnauthorized(mqttModel, resultMessage);
            }
        }
        if (productModel.getDeviceId() != null && productModel.getDeviceId() != 0) {
            if (productModel.getStatus() == 2) {
                return returnUnauthorized(mqttModel, "?????????????????????????????????????????????");
            }
            log.info("-----------????????????????????????,clientId:" + mqttModel.getClientId() + "---------------");
            return ResponseEntity.ok().body("ok");
        } else {
            // ??????????????????
            int result = deviceService.insertDeviceAuto(mqttModel.getDeviceNumber(), mqttModel.getUserId(), mqttModel.getProductId());
            if (result == 1) {
                log.info("-----------????????????????????????,?????????????????????????????????clientId:" + mqttModel.getClientId() + "---------------");
                return ResponseEntity.ok().body("ok");
            }
            return returnUnauthorized(mqttModel, "?????????????????????????????????????????????");
        }
    }

    /**
     * ??????????????????
     *
     * @return
     */
    @Override
    public ResponseEntity encryptAuthentication(MqttAuthenticationModel mqttModel, ProductAuthenticateModel productModel) throws Exception {
        // 1=???????????????2=???????????????3=??????+????????????
        if(productModel.getVertificateMethod()!=2 && productModel.getVertificateMethod()!=3){
            return returnUnauthorized(mqttModel, "????????????????????????????????????????????????????????????");
        }
        String decryptPassword = AESUtils.decrypt(mqttModel.getPassword(), productModel.getMqttSecret());
        if (decryptPassword == null || decryptPassword.equals("")) {
            return returnUnauthorized(mqttModel, "?????????????????????mqtt??????????????????");
        }
        String[] passwordArray = decryptPassword.split("&");
        if (passwordArray.length != 2 && passwordArray.length != 3) {
            // ?????????????????? password & expireTime (& authCode ??????)
            return returnUnauthorized(mqttModel, "?????????????????????mqtt?????????????????????????????? & ???????????? & ????????????????????????????????????");
        }
        String mqttPassword = passwordArray[0];
        Long expireTime = Long.valueOf(passwordArray[1]);
        String authCode = passwordArray.length == 3 ? passwordArray[2] : "";
        // ???????????????
        if (!mqttModel.getUserName().equals(productModel.getMqttAccount())) {
            return returnUnauthorized(mqttModel, "???????????????????????????mqtt???????????????");
        }
        // ????????????
        if (!mqttPassword.equals(productModel.getMqttPassword())) {
            return returnUnauthorized(mqttModel, "???????????????????????????mqtt????????????");
        }
        // ??????????????????
        if (expireTime < System.currentTimeMillis()) {
            return returnUnauthorized(mqttModel, "???????????????????????????mqtt???????????????");
        }
        // ???????????????
        if (productModel.getIsAuthorize() == 1) {
            // ????????????????????????
            String resultMessage = authCodeProcess(authCode, mqttModel, productModel);
            if (!resultMessage.equals("")) {
                return returnUnauthorized(mqttModel, resultMessage);
            }
        }
        // ?????????????????? ???1-????????????2-?????????3-?????????4-?????????
        if (productModel.getDeviceId() != null && productModel.getDeviceId() != 0) {
            if (productModel.getStatus() == 2) {
                return returnUnauthorized(mqttModel, "?????????????????????????????????????????????");
            }
            log.info("-----------????????????????????????,clientId:" + mqttModel.getClientId() + "---------------");
            return ResponseEntity.ok().body("ok");
        } else {
            // ??????????????????
            int result = deviceService.insertDeviceAuto(mqttModel.getDeviceNumber(), mqttModel.getUserId(), mqttModel.getProductId());
            if (result == 1) {
                log.info("-----------????????????????????????,?????????????????????????????????clientId:" + mqttModel.getClientId() + "---------------");
                return ResponseEntity.ok().body("ok");
            }
            return returnUnauthorized(mqttModel, "?????????????????????????????????????????????");
        }
    }

    /**
     * ????????????????????????
     */
    private String authCodeProcess(String authCode, MqttAuthenticationModel mqttModel, ProductAuthenticateModel productModel) {
        String message = "";
        if (authCode.equals("")) {
            return message = "??????????????????????????????????????????";
        }
        // ???????????????????????????
        ProductAuthorize authorize = productAuthorizeMapper.selectFirstAuthorizeByAuthorizeCode(new ProductAuthorize(authCode, productModel.getProductId()));
        if (authorize == null) {
            message = "????????????????????????????????????";
            return message;
        }
        if (authorize.getSerialNumber() != null && !authorize.getSerialNumber().equals("")) {
            // ????????????????????????
            if (!authorize.getSerialNumber().equals( productModel.getSerialNumber())) {
                message = "?????????????????????????????????????????????????????????";
                return message;
            }
        } else {
            // ????????????????????????
            authorize.setSerialNumber(mqttModel.getDeviceNumber());
            authorize.setUserId(mqttModel.getUserId());
            authorize.setUserName("");
            authorize.setUpdateTime(DateUtils.getNowDate());
            // ?????????1-????????????2-????????????
            authorize.setStatus(2);
            int result = productAuthorizeMapper.updateProductAuthorize(authorize);
            if (result != 1) {
                message = "??????????????????????????????????????????";
                return message;
            }
        }
        return message;
    }

    /**
     * ??????????????????
     */
    @Override
    public ResponseEntity returnUnauthorized(MqttAuthenticationModel mqttModel, String message) {
        log.warn("???????????????" + message
                + "\nclientid:" + mqttModel.getClientId()
                + "\nusername:" + mqttModel.getUserName()
                + "\npassword:" + mqttModel.getPassword());
        return ResponseEntity.status(401).body("Unauthorized");
    }
}
