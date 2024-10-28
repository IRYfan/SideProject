package HackerRank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class HourGlass {
    public static void main(String[] args) {
        List<Integer> a = Arrays.asList(1, 1, 1, 0, 0, 0);
        List<Integer> b = Arrays.asList(0, 1, 0, 0, 0, 0);
        List<Integer> c = Arrays.asList(1, 1, 1, 0, 0, 0);
        List<Integer> d = Arrays.asList(0, 0, 2, 4, 4, 0);
        List<Integer> e = Arrays.asList(0, 0, 0, 2, 0, 0);
        List<Integer> f = Arrays.asList(0, 0, 1, 2, 4, 0);
        List<List<Integer>> fun = new ArrayList<>();
        Collections.addAll(fun, a, b, c, d, e, f);
        int result = hourglassSum(fun);
        List<Integer> t = new ArrayList<>(6);
        System.out.println(t.get(0));
    }

    public static int hourglassSum(List<List<Integer>> arr) {
        // Write your code here
        int max = Integer.MIN_VALUE;
        int length = 3;
        int rowOffset = 0;
        int columnOffset = 0;
        while (columnOffset <= length && rowOffset <= length) {
            int hourglassSum = 0;
            for (int row = 0; row < length; row++) {
                List<Integer> tmpRow = arr.get(row + rowOffset);
                for (int column = 0; column < length; column++) {
                    if (row == 1 && (column == 0 || column == 2))
                        continue;
                    int num = tmpRow.get(column + columnOffset);
                    hourglassSum += num;
                }
            }
            max = Math.max(hourglassSum, max);
            columnOffset++;
            if (columnOffset > length) {
                columnOffset = 0;
                rowOffset++;
            }
        }
        return max;
    }

    // prefix sum
    public static long arrayManipulation(int n, List<List<Integer>> queries) {
        long[] array = new long[n + 2];
        for (int i = 0; i < queries.size(); i++) {
            long numberToAdd = queries.get(i).get(2);
            int startIndex = queries.get(i).get(0);
            int endIndex = queries.get(i).get(1);

            array[startIndex] += numberToAdd;
            array[endIndex + 1] -= numberToAdd;
        }
        long max = 0;
        for (int i = 1; i < array.length; i++) {
            array[i] += array[i - 1];
            max = Math.max(array[i], max);
        }
        return max;
    }
}
