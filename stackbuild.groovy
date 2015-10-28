// https://docs.aws.amazon.com/AmazonS3/latest/dev/PresignedUrlUploadObjectDotNetSDK.html

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.auth.BasicAWSCredentials;

def presignUrl(id, key, objectKey) {
  def cred = new BasicAWSCredentials(id, key)

  //AmazonS3 s3Client = new AmazonS3Client(new ProfileCredentialsProvider());
  AmazonS3 s3Client = new AmazonS3Client(cred);

  java.util.Date expiration = new java.util.Date();
  long msec = expiration.getTime();
  msec += 1000 * 60 * 180; // Add 3 hours.
  expiration.setTime(msec);

  def bucketName = "jenkins-art-test"

  GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucketName, objectKey);
  generatePresignedUrlRequest.setMethod(HttpMethod.PUT);
  generatePresignedUrlRequest.setExpiration(expiration);

  URL s = s3Client.generatePresignedUrl(generatePresignedUrlRequest);
  return s
}

// https://issues.jenkins-ci.org/browse/JENKINS-27389
def credLookup(name) {
  def v
  withCredentials([[$class: 'StringBinding', credentialsId: name, variable: 'foo']]) {
    v = env.foo
  }
  return v
}

// https://stackoverflow.com/questions/25692515/groovy-built-in-rest-http-client
def mac = new URL("http://169.254.169.254/latest/meta-data/mac").getText()
def subnet_id = new URL("http://169.254.169.254/latest/meta-data/network/interfaces/macs/${mac}/subnet-id").getText()
def security_group_ids = new URL("http://169.254.169.254/latest/meta-data/network/interfaces/macs/${mac}/security-group-ids").getText().split("\n")

node('vagrant') {
  git url: 'https://github.com/jhoblitt/sandbox-stackbuild.git', branch: 'aws'
  def v
  env.AWS_ACCESS_KEY_ID = credLookup('e2d6762c-c394-44bb-82fd-47622785f854')
  env.AWS_SECRET_ACCESS_KEY = credLookup('3d39fa47-7460-40a8-8b08-a0ab0b0171f8')
  env.AWS_DEFAULT_REGION = credLookup('d5fe6c73-611b-4bd1-a432-95ff7872c594')

  env.AWS_SUBNET_ID = subnet_id
  env.AWS_SECURITY_GROUPS = security_group_ids.join(" ")

  def dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd")
  def date = new java.util.Date()
  def dateStamp = dateFormat.format(date)

  def objectKey = "newinstall.sh-$env.BUILD_ID-$BOX-$dateStamp.tar.gz"
  echo "objectKey: $objectKey"

  def S3_URL = presignUrl(
      env.AWS_ACCESS_KEY_ID,
      env.AWS_SECRET_ACCESS_KEY,
      objectKey)
  echo S3_URL.toString()

  echo env.AWS_ACCESS_KEY_ID
  echo env.AWS_DEFAULT_REGION
  echo env.AWS_SUBNET_ID
  echo env.AWS_SECURITY_GROUPS
  //echo env.S3_URL
  echo "BUILD_ID: " + env.BUILD_ID

  // vagrant burned on 2015-10-27
  env.CENTOS7_AMI = 'ami-2c0e7f46'

  try {
    sh "vagrant up $BOX --destroy-on-error --provider=$PROVIDER"
    sh """
ARGS=()

if [ ! -z "$TAG" ]; then
  ARGS+=('-t')
  ARGS+=("$TAG")
fi
ARGS+=("$PRODUCTS")

vagrant ssh $BOX <<END
set -o errexit

set -o verbose
if grep -q -i "CentOS release 6" /etc/redhat-release; then
  . /opt/rh/devtoolset-3/enable
fi
set +o verbose

source ./stack/loadLSST.bash
eups distrib install \${ARGS[@]}
END
"""
    sh """
vagrant ssh $BOX <<END
set -o errexit

sudo yum install -y pigz
time tar -c --file=/tmp/foo.tar.gz --use-compress-program=pigz --directory=/home/vagrant stack
END
"""

    sh """
vagrant ssh $BOX <<END
set -o errexit

curl --fail --upload-file /tmp/foo.tar.gz '$S3_URL'
END
"""

  }
  finally {
    sh "vagrant destroy --force $BOX"
  }
}
