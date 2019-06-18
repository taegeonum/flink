package org.apache.flink.runtime;

import java.util.logging.Logger;

public class LogUtils {

	private static final Logger LOG = Logger.getLogger(LogUtils.class.getName());

	public static void printStackTrace() {
		StackTraceElement[] elements = Thread.currentThread().getStackTrace();
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i < elements.length; i++) {
			StackTraceElement s = elements[i];
			sb.append("\tat " + s.getClassName() + "." + s.getMethodName()
				+ "(" + s.getFileName() + ":" + s.getLineNumber() + ")\n");
		}

		LOG.info(sb.toString());
	}
}
