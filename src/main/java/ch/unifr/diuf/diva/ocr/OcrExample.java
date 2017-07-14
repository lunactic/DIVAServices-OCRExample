package ch.unifr.diuf.diva.ocr;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Marcel Würsch
 * marcel.wuersch@unifr.ch
 * http://diuf.unifr.ch/main/diva/home/people/marcel-w%C3%BCrsch
 * Created on: 7/14/2017.
 */
public class OcrExample {

    /**
     * Log4j logger
     */
    private static final Logger logger = Logger.getLogger(OcrExample.class);


    public static void main(String args[]) {
        OcrExample example = new OcrExample();
        //upload training data
        example.uploadData("trainData", "ocr_train_example");
        //start training process
        example.runTraining("ocr_train_example", 46, 500, 100);
        //upload testing data and model
        example.uploadModel("outputs/models/minModel.pyrnn.gz");
        example.uploadData("recoData", "ocr_reco_example");
        //run recognition
        example.runRecognition("ocr_reco_example", "ocr_models/greekPoly.gz");

    }

    public void uploadModel(String filePath) {
        File file = new File(filePath);
        List<Map<String, String>> fileValues = new ArrayList<>();
        try {
            HashMap<String, String> values = new HashMap<>();
            JSONObject reqBody = new JSONObject();
            //parse the stuff into the JSON Object
            JSONArray files = new JSONArray();
            JSONObject object = new JSONObject();
            object.put("type", "image");
            object.put("value", encodeFileBase64(file));
            object.put("name", "greekPoly");
            object.put("extension", FilenameUtils.getExtension(file.getName()));
            files.put(object);
            reqBody.put("files", files);

            HttpResponse<String> response = Unirest.put("http://divaservices.unifr.ch/api/v2/collections/ocr_models")
                    .header("content-type", "application/json")
                    .body(reqBody.toString())
                    .asString();
            logger.info(response.getBody());
        } catch (UnirestException e) {
            e.printStackTrace();
        }
    }

    public void uploadData(String folderLocation, String collectionName) {
        ClassLoader classLoader = getClass().getClassLoader();
        File dataFolder = new File(classLoader.getResource(folderLocation).getFile());

        List<Map<String, String>> fileValues = new ArrayList<>();
        try {
            for (File file : dataFolder.listFiles()) {
                HashMap<String, String> values = new HashMap<>();
                String contentType = Files.probeContentType(file.toPath());
                values.put("name", FilenameUtils.getBaseName(file.getName()));
                values.put("extension", FilenameUtils.getExtension(file.getName()));
                switch (contentType) {
                    case "application/xml":
                    case "text/xml":
                    case "text/plain":
                    case "text/csv":
                        values.put("type", "text");
                        values.put("value", readFile(file.getPath(), StandardCharsets.UTF_8));
                        break;
                    case "image/png":
                    case "image/jpeg":
                        values.put("type", "image");
                        values.put("value", encodeImageBase64(file));
                        break;
                    default:
                        logger.error("default switch case...");
                }
                fileValues.add(values);
            }
            JSONObject reqBody = new JSONObject();
            reqBody.put("name", collectionName);
            //parse the stuff into the JSON Object
            JSONArray files = new JSONArray();
            for (Map<String, String> values : fileValues) {
                JSONObject object = new JSONObject();
                object.put("type", values.get("type"));
                object.put("value", values.get("value"));
                object.put("name", values.get("name"));
                object.put("extension", values.get("extension"));
                files.put(object);
            }
            reqBody.put("files", files);
            //fetch collection name
            HttpResponse<JsonNode> response = Unirest.post("http://divaservices.unifr.ch/api/v2/collections")
                    .header("content-type", "application/json")
                    .body(reqBody.toString())
                    .asJson();

            JSONObject jsonResponse = response.getBody().getObject();
            if (!jsonResponse.has("collection")) {
                logger.error("Collection most likely already existed");
            }
        } catch (Exception ex) {
            logger.error(ex.getLocalizedMessage(), ex);
        }
    }

    void runTraining(String collectionName, int lineHeight, int trainIterations, int saveFrequency) {
        ClassLoader classLoader = getClass().getClassLoader();
        try {
            HttpResponse<JsonNode> response = Unirest.post("http://divaservices.unifr.ch/api/v2/ocr/ocropustraining/1")
                    .header("content-type", "application/json")
                    .body("{\"data\":[{\"inputData\":\"" + collectionName + "\"}],\"parameters\":{\"lineHeight\":" + lineHeight + ",\"trainIteration\":" + trainIterations + ",\"saveFreq\":" + saveFrequency + "}}")
                    .asJson();
            JSONArray results = response.getBody().getObject().getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject resultObject = results.getJSONObject(i);
                String link = resultObject.getString("resultLink");

                JsonNode resultResponse = getResponse(link);

                JSONArray output = resultResponse.getObject().getJSONArray("output");
                for (int j = 0; j < output.length(); j++) {
                    JSONObject object = output.getJSONObject(j);
                    if (object.has("file")) {
                        JSONObject fileValues = object.getJSONObject("file");
                        if (fileValues.getString("name").contains("minModel")) {
                            //download min model
                            String url = fileValues.getString("url");
                            //TODO: change this paths accordingly
                            File outputFile = new File("outputs/models/minModel.pyrnn.gz");
                            FileUtils.copyURLToFile(new URL(url), outputFile);
                        } else if (fileValues.getString("name").contains("trainingError")) {
                            //download visualization
                            String url = fileValues.getString("url");
                            //TODO: change this paths accordingly
                            File outputFile = new File("outputs/visualization/trainingError.png");
                            FileUtils.copyURLToFile(new URL(url), outputFile);
                        }
                    }
                }
            }

        } catch (UnirestException | IOException e) {
            e.printStackTrace();
        }
    }

    void runRecognition(String collectionName, String modelIdentifier) {
        try {
            HttpResponse<JsonNode> response = Unirest.post("http://divaservices.unifr.ch/api/v2/ocr/ocropusrecognize/1")
                    .header("content-type", "application/json")
                    .body("{\"data\":[{\"recognitionData\":\"" + collectionName + "\", \"recognitionModel\":\"" + modelIdentifier + "\"}]}")
                    .asJson();
            JSONArray results = response.getBody().getObject().getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject resultObject = results.getJSONObject(i);
                String link = resultObject.getString("resultLink");
                JsonNode resultResponse = getResponse(link);
                JSONArray output = resultResponse.getObject().getJSONArray("output");
                for (int j = 0; j < output.length(); j++) {
                    JSONObject object = output.getJSONObject(j);
                    if (object.has("file")) {
                        JSONObject fileValues = object.getJSONObject("file");
                        //download visualization
                        String name = fileValues.getString("name");
                        String url = fileValues.getString("url");
                        //TODO: change this paths accordingly
                        File outputFile = new File("recognizedText/" + name);
                        FileUtils.copyURLToFile(new URL(url), outputFile);
                    }
                }
            }
        } catch (IOException | UnirestException e1) {
            e1.printStackTrace();
        }
    }


    /**
     * Read a file
     *
     * @param path     the file path
     * @param encoding the file encoding
     * @return the file content as a string
     */
    private String readFile(String path, Charset encoding) {
        logger.trace(Thread.currentThread().getStackTrace()[1].getMethodName());
        try {
            byte[] encoded = Files.readAllBytes(Paths.get(path));
            return new String(encoded, encoding);
        } catch (IOException e) {
            logger.error(e);
            if (logger.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Encode a file to Base64
     *
     * @param file the file to be encoded
     * @return a base64 string
     */
    private String encodeImageBase64(File file) {
        logger.trace(Thread.currentThread().getStackTrace()[1].getMethodName());
        try {
            BufferedImage image = ImageIO.read(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            return Base64.encodeBase64String(bytes);
        } catch (IOException e) {
            logger.error(e);
            if (logger.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private String encodeFileBase64(File file) {
        try {
            byte[] bytes = IOUtils.toByteArray(new FileInputStream(file));
            return Base64.encodeBase64String(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private JsonNode getResponse(String url) {
        try {
            HttpResponse<JsonNode> resultResponse = Unirest.get(url).asJson();
            while (resultResponse.getBody().getObject().getString("status").equals("planned")) {
                Thread.sleep(5000);
                resultResponse = Unirest.get(url).asJson();
            }
            return resultResponse.getBody();
        } catch (InterruptedException | UnirestException e) {
            e.printStackTrace();
        }
        return null;
    }
}
