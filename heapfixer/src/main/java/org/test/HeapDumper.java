package org.test;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class HeapDumper {
    public static void main(String[] args) {
        System.out.println("Starting heap allocation test. This program will retain allocated byte[] chunks until the JVM runs out of heap.");

        // Keep references to the arrays so they are not garbage-collected
        java.util.List<byte[]> list = new java.util.ArrayList<>();
        final int ONE_MB = 1024 * 1024;
        int allocatedMb = 0;

        // Keep allocating 1 MB blocks until an OOM occurs. If you run the JVM with -Xmx512m
        // (e.g. `java -Xmx512m -cp ... org.test.Main`) this will reliably trigger a java.lang.OutOfMemoryError
        try {
            while (true) {
                list.add(new byte[ONE_MB]);
                allocatedMb++;

                // Print progress occasionally
                if (allocatedMb % 16 == 0) {
                    System.out.println("Allocated approximately " + allocatedMb + " MB");
                }
            }
        } catch (OutOfMemoryError oom) {
            System.err.println("OutOfMemoryError: allocated approx " + allocatedMb + " MB before failure");
            oom.printStackTrace();
            // rethrow so the process terminates with an error
            throw oom;
        }
    }
}