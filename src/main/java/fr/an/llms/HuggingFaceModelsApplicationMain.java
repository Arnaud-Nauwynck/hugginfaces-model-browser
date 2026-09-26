package fr.an.llms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HuggingFaceModelsApplicationMain {

    public static void main(String[] args) {
        try {
            SpringApplication.run(HuggingFaceModelsApplicationMain.class, args);
        } catch(Throwable ex) {
            System.out.println("Failed .. exiting!");
            throw ex;
        }
    }

}
