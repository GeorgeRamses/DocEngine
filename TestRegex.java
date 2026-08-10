import java.util.regex.*;

public class TestRegex {
    public static void main(String[] args) {
        String s = "2-0274";
        String norm = normalizeOcrDigits(s);
        System.out.println("Normalized: " + norm);
        
        Pattern p = Pattern.compile("\\b\\d{6}\\b");
        Matcher m1 = p.matcher(s);
        Matcher m2 = p.matcher(norm);
        
        if (m1.find()) {
            System.out.println("Found in original: " + m1.group());
        } else if (m2.find()) {
            System.out.println("Found in normalized: " + m2.group());
        } else {
            System.out.println("Not found!");
        }
    }
    
    public static String normalizeOcrDigits(String s) {
        return s.replaceAll("(?i)(?<=[0-9OolIBSZo\\-])O(?=[0-9OolIBSZo\\-])", "0")
                .replaceAll("(?<=[0-9])l(?=[0-9])", "1")
                .replaceAll("(?<=[0-9])I(?=[0-9])", "1")
                .replaceAll("(?<=[0-9])B(?=[0-9])", "8")
                .replaceAll("(?<=[0-9])S(?=[0-9])", "5")
                .replaceAll("(?<=[0-9])Z(?=[0-9])", "2")
                .replaceAll("(?<=[0-9])[\\-—_](?=[0-9])", "1");
    }
}
