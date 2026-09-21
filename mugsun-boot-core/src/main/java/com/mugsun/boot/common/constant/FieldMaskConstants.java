package com.mugsun.boot.common.constant;

/**
 * 字段级权限常量：脱敏类型名（@ColumnMask 值 / MaskManager 处理器键）与配套权限码。
 * <p>三级可见性由权限码决定——无查看权→列不可见；有查看权无明文权→脱敏；有明文权→明文。
 */
public interface FieldMaskConstants {

	/** 手机号脱敏类型 */
	String TYPE_USER_PHONE = "user_phone";
	/** 身份证脱敏类型 */
	String TYPE_USER_ID_CARD = "user_id_card";

	/** 手机号查看权（脱敏展示）；缺失则该列不可见 */
	String PERM_USER_PHONE = "sys:user:phone";
	/** 手机号明文权 */
	String PERM_USER_PHONE_PLAIN = "sys:user:phone:plain";
	/** 身份证查看权（脱敏展示）；缺失则该列不可见 */
	String PERM_USER_ID_CARD = "sys:user:idcard";
	/** 身份证明文权 */
	String PERM_USER_ID_CARD_PLAIN = "sys:user:idcard:plain";
}
