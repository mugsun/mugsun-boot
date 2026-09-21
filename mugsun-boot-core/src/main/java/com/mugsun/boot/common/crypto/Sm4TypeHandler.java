package com.mugsun.boot.common.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SM4 字段加解密 TypeHandler：入库自动加密、查询自动解密（存密文、返回明文）。
 * 用法：实体字段加 {@code @Column(typeHandler = Sm4TypeHandler.class)}。
 */
@MappedTypes(String.class)
public class Sm4TypeHandler extends BaseTypeHandler<String> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
		ps.setString(i, Sm4Util.encrypt(parameter));
	}

	@Override
	public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return Sm4Util.decrypt(rs.getString(columnName));
	}

	@Override
	public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return Sm4Util.decrypt(rs.getString(columnIndex));
	}

	@Override
	public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return Sm4Util.decrypt(cs.getString(columnIndex));
	}
}
