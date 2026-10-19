package com.mugsun.boot.common.excel;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFilesTest {

	private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0};
	private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

	private static MockMultipartFile f(String name, byte[] b) {
		return new MockMultipartFile("file", name, null, b);
	}

	@Test
	void rejectsMissingEmptyAndMismatched() {
		assertThatThrownBy(() -> ImportFiles.checkExcel(null)).isInstanceOf(ServiceException.class).hasMessage(ImportFiles.MSG_NO_FILE);
		assertThatThrownBy(() -> ImportFiles.checkExcel(f("a.xlsx", new byte[0]))).hasMessage(ImportFiles.MSG_EMPTY);
		assertThatThrownBy(() -> ImportFiles.checkExcel(f("a.xlsx", "hello".getBytes()))).hasMessage(ImportFiles.MSG_BAD_FORMAT);
		assertThatThrownBy(() -> ImportFiles.checkExcel(f("a.xlsx", OLE2))).hasMessage(ImportFiles.MSG_BAD_FORMAT);
		assertThatThrownBy(() -> ImportFiles.checkExcel(f("a.xls", ZIP))).hasMessage(ImportFiles.MSG_BAD_FORMAT);
		assertThatThrownBy(() -> ImportFiles.checkExcel(f("a.csv", ZIP))).hasMessage(ImportFiles.MSG_BAD_FORMAT);
	}

	@Test
	void acceptsMatchingMagic() {
		assertThatCode(() -> ImportFiles.checkExcel(f("a.XLSX", ZIP))).doesNotThrowAnyException();
		assertThatCode(() -> ImportFiles.checkExcel(f("a.xls", OLE2))).doesNotThrowAnyException();
		assertThatCode(() -> ImportFiles.checkExcel(f(null, ZIP))).doesNotThrowAnyException();
	}

	@Test
	void corruptZipIsBadFormatNot500() {
		assertThatThrownBy(() -> ImportFiles.readExcel(f("a.xlsx", ZIP), Object.class)).hasMessage(ImportFiles.MSG_BAD_FORMAT);
	}
}
