package controller;

import io.netty.handler.timeout.TimeoutException;
import model.SdkUserImpl;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import testutils.TestUtils;

import java.io.File;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class Controller {
    private static NetworkConfig networkConfig;
    private String filePath="";
    public Channel constructChannel(HFClient client, String channelName) throws Exception {
        if(networkConfig==null) {
            networkConfig = NetworkConfig.fromYamlFile(new File(filePath));
        }
        Channel newChannel = client.loadChannelFromConfig(channelName, networkConfig);
        if (newChannel == null) {
            throw new RuntimeException("Channel " + channelName + " is not defined in the config file!");
        }
        return newChannel.initialize();
    }

    public HFClient getTheClient(String orgName) throws Exception {
        if(networkConfig==null) {
            networkConfig = NetworkConfig.fromYamlFile(new File(filePath));
        }
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        NetworkConfig.UserInfo userInfo = networkConfig.getPeerAdmin(orgName);
        String userName = userInfo.getName();
        String mspId = userInfo.getMspId();
        PrivateKey privateKey = userInfo.getEnrollment().getKey();
        String signedCert = userInfo.getEnrollment().getCert();
        TestUtils.MockUser admin = TestUtils.getMockUser(userName, mspId);
        admin.setEnrollment(TestUtils.getMockEnrollment(privateKey, signedCert));
        client.setUserContext(admin);
        return client;
    }
    public boolean invokeChaincode(ChaincodeID chaincodeID, String function, String[] args, Channel channel, HFClient client) throws InterruptedException, TimeoutException, java.util.concurrent.TimeoutException, ExecutionException {

        Collection<ProposalResponse> invokePropResp = null;
        try {

            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();
            TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
            transactionProposalRequest.setChaincodeID(chaincodeID);
            transactionProposalRequest.setFcn(function);
            transactionProposalRequest.setArgs(args);
            transactionProposalRequest.setProposalWaitTime(10000000);
            transactionProposalRequest.setUserContext(client.getUserContext());
            invokePropResp = channel.sendTransactionProposal(transactionProposalRequest);
            for (ProposalResponse response : invokePropResp) {
                if (response.getStatus() == ChaincodeResponse.Status.SUCCESS) {
                    System.out.printf("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                    successful.add(response);
                } else {
                    failed.add(response);
                }
            }
            Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(invokePropResp);
            if (proposalConsistencySets.size() != 1) {
                throw new ProposalException("Expected only one set of consistent move proposal responses but got "+ proposalConsistencySets.size());
            }
            System.out.printf("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                    invokePropResp.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                throw new ProposalException(firstTransactionProposalResponse.getMessage());
            }
            System.out.printf("Successfully received transaction proposal responses.");
            System.out.printf("Sending chaincode transaction to orderer.");
        } catch (Exception e) {
            e.printStackTrace();
        }
        CompletableFuture<BlockEvent.TransactionEvent> tx = channel.sendTransaction(invokePropResp);
        BlockEvent.TransactionEvent txevent = tx.get(30, TimeUnit.SECONDS);

        return txevent.isValid();
    }
    public SdkUserImpl enrolCaUser(HFClient client, Channel channel) throws Exception
    {
        NetworkConfig.OrgInfo orgInfo = networkConfig.getOrganizationInfo("Org1");
        String orgName = orgInfo.getName();
        String mspID = orgInfo.getMspId();
        NetworkConfig.UserInfo adminInfo = networkConfig.getPeerAdmin("Org1");
        String adminUserName = adminInfo.getName();
        String adminMspId = adminInfo.getMspId();
        SdkUserImpl admin = new SdkUserImpl(adminUserName, adminMspId);
        NetworkConfig.CAInfo caInfo = orgInfo.getCertificateAuthorities().iterator().next();
        System.out.println(caInfo.getUrl());
        HFCAClient ca = HFCAClient.createNewInstance(caInfo.getCAName(), caInfo.getUrl(), caInfo.getHttpOptions());
        ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        Enrollment adminEnrollment = ca.enroll(adminUserName, adminInfo.getEnrollSecret());
        admin.setEnrollment(adminEnrollment);
        SdkUserImpl user = new SdkUserImpl("User1", orgName);
        RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
        user.setEnrollmentSecret(ca.register(rr, admin));
        user.setEnrollment(ca.enroll(user.getName(),user.getEnrollmentSecret()));
        user.setMspId(mspID);
        client.setUserContext(user);
        return user;
    }
    public boolean installChaincode(HFClient client, Channel channel, String ccFilePath, ChaincodeID chaincodeID) {

        boolean status = false;
        try {
            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> responses;
            InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
            installProposalRequest.setChaincodeSourceLocation(new File(ccFilePath));
            installProposalRequest.setChaincodeID(chaincodeID);
            installProposalRequest.setChaincodeVersion(chaincodeID.getVersion());
            responses = client.sendInstallProposal(installProposalRequest,channel.getPeers());
            for (ProposalResponse response : responses) {
                if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                }
            }
            if (responses.size()==successful.size()){
                status=true;
            }

        }
        catch (Exception e){
            e.printStackTrace();
        }
        return status;
    }

    public boolean instantiateChaincode(HFClient client, Channel channel, ChaincodeID chaincodeID, String fcn, String[] args, String pathToEndorsmentPolicy){
        boolean status = false;
        try {
            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
            instantiateProposalRequest.setChaincodeID(chaincodeID);
            instantiateProposalRequest.setFcn(fcn);
            instantiateProposalRequest.setArgs(args);
            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes("UTF-8"));
            tm.put("method", "InstantiateProposalRequest".getBytes("UTF-8"));
            instantiateProposalRequest.setTransientMap(tm);
            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(new File(pathToEndorsmentPolicy));
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);
            Collection<ProposalResponse> responses = channel.sendInstantiationProposal(instantiateProposalRequest);
            CompletableFuture<BlockEvent.TransactionEvent> future = channel.sendTransaction(responses);
            BlockEvent.TransactionEvent event = future.get(30, TimeUnit.SECONDS);
            status = event.isValid();
        }catch (Exception e){
            e.printStackTrace();
        }
        return status;
    }
}

