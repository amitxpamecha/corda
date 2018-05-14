package com.template.ledger.flow;

import com.google.common.collect.ImmutableList;
import com.template.ledger.common.constants.GoldBlockConstants;
import net.corda.core.contracts.*;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.core.utilities.ProgressTracker;
import net.corda.finance.contracts.Commodity;
import net.corda.finance.contracts.asset.CommodityContract;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static net.corda.core.crypto.CryptoUtils.generateKeyPair;

/**
 * Flow to issue some goldblocks to seller for selling it to buyer.
 */
@InitiatingFlow
@StartableByRPC
public class BlockIssueFlow extends FlowLogic<SignedTransaction> implements GoldBlockConstants {

    /*Write progress tracker*/
    private static final ProgressTracker.Step IDENTIFY_OTHER_NODES = new ProgressTracker.Step("Identifying other nodes on the network.");
    private static final ProgressTracker.Step OTHER_TX_COMPONENTS = new ProgressTracker.Step("Gathering a transaction's other components.");
    private static final ProgressTracker.Step TX_BUILDING = new ProgressTracker.Step("Building a transaction.");
    private static final ProgressTracker.Step TX_VERIFICATION = new ProgressTracker.Step("Verifying a transaction.");
    private static final ProgressTracker.Step TX_SIGNING = new ProgressTracker.Step("Signing a transaction.");
    private static final ProgressTracker.Step SIGS_GATHERING = new ProgressTracker.Step("Gathering a transaction's signatures.") {
        // Wiring up a child progress tracker allows us to see the
        // subflow's progress steps in our flow's progress tracker.
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.tracker();
        }
    };
    private static final ProgressTracker.Step VERIFYING_SIGS = new ProgressTracker.Step("Verifying a transaction's signatures.");
    private static final ProgressTracker.Step FINALISATION = new ProgressTracker.Step("Finalising a transaction.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.tracker();
        }
    };
    private final ProgressTracker progressTracker = new ProgressTracker(
            IDENTIFY_OTHER_NODES,
            OTHER_TX_COMPONENTS,
            TX_BUILDING,
            TX_SIGNING,
            TX_VERIFICATION,
            SIGS_GATHERING,
            VERIFYING_SIGS,
            FINALISATION
    );
    private int orderValue;
    private Party regulator;
    private Amount<Issued<Commodity>> issuedBlocks;
    private CordaX500Name notaryName;
    private StateAndContract outputStateAndContract;
    private Command<CommodityContract.Commands.Issue> issueAssetCommand;
    private TimeWindow txnTimeWindow;
    private TransactionBuilder txBuilder;
    private SignedTransaction onceSignedTx;
    private SignedTransaction fullySignedTx;
    private SignedTransaction notarisedTx;

    public BlockIssueFlow(int orderValue) {
        this.orderValue = orderValue;
        final OpaqueBytes ref = OpaqueBytes.of((byte) 0x01);
        PartyAndReference partyAndReference = new PartyAndReference(getServiceHub().getMyInfo().getLegalIdentities().get(0), ref);
        Commodity goldblocks = new Commodity(GOLD_BLOCK_UNIQUE_CODE, GOLD_BLOCK_DISPLAY_NAME, 2);
        Issued<Commodity> issuedCommodity = new Issued<Commodity>(partyAndReference, goldblocks);
        Amount<Issued<Commodity>> issuedBlocks = new Amount<Issued<Commodity>>(orderValue, issuedCommodity);
        this.issuedBlocks = issuedBlocks;
    }

    @Override
    public SignedTransaction call() throws FlowException {
        PublicKey dummyPubKey = generateKeyPair().getPublic();

        //STAGE-1 - IDENTIFY_OTHER_NODES
        progressTracker.setCurrentStep(IDENTIFY_OTHER_NODES);
        identifyOtherNodes();

        //STAGE-2 - OTHER_TX_COMPONENTS
        progressTracker.setCurrentStep(OTHER_TX_COMPONENTS);
        generateOtherTxnComponents();

        //STAGE-3 -  TX_BUILDING
        progressTracker.setCurrentStep(TX_BUILDING);
        buildTransaction();

        //STAGE-4 - TX_SIGNING
        progressTracker.setCurrentStep(TX_SIGNING);
        signTransaction();

        try {

            //STAGE-5 - TX_VERIFICATION
            progressTracker.setCurrentStep(TX_VERIFICATION);
            verifyTransaction();

            //STAGE-6 - SIGS_GATHERING
            progressTracker.setCurrentStep(SIGS_GATHERING);
            gatherSignatures();

            //STAGE-7 - VERIFYING_SIGS
            progressTracker.setCurrentStep(VERIFYING_SIGS);
            verfiySignatures();

        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }

        //STAGE-8 - FINALISATION
        progressTracker.setCurrentStep(FINALISATION);
        finalisingTransaction();

        return null;
    }

    private void identifyOtherNodes(){
        //progressTracker.setCurrentStep(IDENTIFY_OTHER_NODES);
        //Retrieve notary from network map
        notaryName = new CordaX500Name(GOLD_BLOCK_NOTARY_SERVICE, GOLD_BLOCK_NOTARY_CITY, GOLD_BLOCK_NOTARY_COUNTRY);
        regulator = getServiceHub().getNetworkMapCache().getNotary(notaryName);

    }

    private void generateOtherTxnComponents(){
        //progressTracker.setCurrentStep(OTHER_TX_COMPONENTS);

        CommodityContract.State outputState = new CommodityContract.State(issuedBlocks, getServiceHub().getMyInfo().getLegalIdentities().get(0));
        outputStateAndContract = new StateAndContract(outputState, CommodityContract.Companion.toString());

        CommodityContract.Commands.Issue commandData = new CommodityContract.Commands.Issue();
        PublicKey ourPubKey = getServiceHub().getMyInfo().getLegalIdentitiesAndCerts().get(0).getOwningKey();
        List<PublicKey> requiredSigners = ImmutableList.of(ourPubKey);
        issueAssetCommand = new Command<>(commandData, requiredSigners);

        /*Time windows represent the period of time during which a transaction must be notarised.
        They can have a start and an end time, or be open at either end.*/
        txnTimeWindow = TimeWindow.fromStartAndDuration(getServiceHub().getClock().instant(), Duration.ofSeconds(30));
    }

    private void buildTransaction() throws TransactionResolutionException, TransactionVerificationException, AttachmentResolutionException {
        //progressTracker.setCurrentStep(TX_BUILDING);

        // If transaction has input states or a time-window, instantiate it with a notary.
        txBuilder = new TransactionBuilder(regulator);

        // Add items to the transaction builder
        txBuilder.withItems(
                // Inputs, as StateAndRef that reference to the outputs of previous transactions
                null,
                // Outputs, as StateAndContract
                outputStateAndContract,
                // Command
                issueAssetCommand,
                // Attachments, as SecureHash
                null,
                // A txn time-window, as TimeWindow
                txnTimeWindow
        );

    }

    private void signTransaction(){
        //progressTracker.setCurrentStep(TX_SIGNING);

        // Finalise the transaction by signing it & converting it into a SignedTransaction
        onceSignedTx = getServiceHub().signInitialTransaction(txBuilder);
    }

    private void verifyTransaction() throws FlowException, SignatureException {
        //progressTracker.setCurrentStep(TX_VERIFICATION);
        onceSignedTx.verify(getServiceHub());

    }

    private void gatherSignatures() throws FlowException {
        //progressTracker.setCurrentStep(SIGS_GATHERING);
         /*The list of parties who need to sign a transaction is dictated by the transaction's commands. Once we've signed a transaction
         ourselves, we can automatically gather the signatures of the other required signers using ``CollectSignaturesFlow``.
         The responder flow will need to call ``SignTransactionFlow``*/
        fullySignedTx = subFlow(new CollectSignaturesFlow(onceSignedTx, Collections.emptySet(), SIGS_GATHERING.childProgressTracker()));

    }

    private void verfiySignatures() throws SignatureException {
        //progressTracker.setCurrentStep(VERIFYING_SIGS);
        // Verify that a transaction has all the required signatures, and that they're all valid.
        fullySignedTx.verifyRequiredSignatures();
    }

    private void finalisingTransaction() throws FlowException {
        //progressTracker.setCurrentStep(FINALISATION);

        // Notarise the transaction and get it recorded in the vault of the participants of all the transaction's states.
        notarisedTx = subFlow(new FinalityFlow(fullySignedTx, FINALISATION.childProgressTracker()));

    }

}
